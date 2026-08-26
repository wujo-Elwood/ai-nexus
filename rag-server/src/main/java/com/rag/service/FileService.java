package com.rag.service;

import com.rag.ai.EmbeddingService;
import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.KbFile;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.FileMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.rag.DocumentParser;
import com.rag.rag.QdrantService;
import com.rag.rag.TextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/**
 * 文件服务
 * 负责文件的上传、解析、切片、向量化和删除
 * 文件处理在后台线程异步执行，不阻塞前端请求
 */
@Slf4j
@Service
public class FileService {

    @Autowired
    private FileMapper fileMapper;
    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private ChunkMapper chunkMapper;
    @Autowired
    private DocumentParser documentParser;
    @Autowired
    private TextSplitter textSplitter;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private QdrantService qdrantService;
    @Autowired
    @Qualifier("fileProcessExecutor")
    private Executor fileProcessExecutor;

    @Value("${file.upload-dir}")
    private String uploadDir;
    @Value("${qdrant.upsert-batch-size:100}")
    private int qdrantUpsertBatchSize;
    @Value("${file.process.max-attempts:3}")
    private int maxProcessAttempts;
    @Value("${file.process.retry-delay-seconds:60}")
    private long retryDelaySeconds;

    private static final Set<String> allowedTypes = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
    );

    /**
     * 上传文件并创建后台处理任务
     * 保存文件到磁盘，写入数据库记录，然后异步执行解析和向量化
     */
    @Transactional
    public KbFile upload(Long kbId, MultipartFile file) {
        String contentType = file.getContentType();
        if (!isAllowedType(contentType, file.getOriginalFilename())) {
            throw new BusinessException(400, "Unsupported file type. Allowed: PDF, DOCX, TXT");
        }

        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path uploadPath = Paths.get(uploadDir);
        try {
            //创建目录
            Files.createDirectories(uploadPath);
            //文件名拼接到目录路径
            Path filePath = uploadPath.resolve(fileName);
            //上传的文件保存到磁盘上的指定位置
            file.transferTo(filePath.toFile());

            //调用统一的已落盘文件登记方法，保持普通上传和分片上传字段一致
            return registerSavedFile(kbId, file.getOriginalFilename(), contentType, file.getSize(), filePath);
        } catch (IOException e) {
            throw new BusinessException("Failed to save file: " + e.getMessage());
        }
    }

    /** 兼容部分客户端未填写 MIME 的情况，按安全扩展名兜底判断。 */
    private boolean isAllowedType(String contentType, String originalName) {
        if (contentType != null && allowedTypes.contains(contentType.split(";")[0])) return true;
        if (originalName == null) return false;
        String name = originalName.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".xlsx") || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp");
    }

    /**
     * 登记已经保存到磁盘的文件并提交后台处理任务
     * 分片上传合并完成后复用此方法，避免产生第二套文件处理流程
     */
    @Transactional
    public KbFile registerSavedFile(Long kbId, String originalFileName, String contentType,
                                    long fileSize, Path filePath) {
        String fileSha256 = calculateSha256(filePath);
        //同一知识库同一摘要直接拒绝，避免重复切片和向量写入
        KbFile duplicate = fileMapper.findBySha256(kbId, fileSha256);
        if (duplicate != null) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ignored) {
                log.warn("Failed to delete duplicate file {}", filePath);
            }
            throw new BusinessException(409, "文件内容已存在，不能重复上传");
        }
        KbFile previous = fileMapper.findCurrentByName(kbId, originalFileName);
        KbFile kbFile = new KbFile();
        kbFile.setKbId(kbId);
        kbFile.setFileName(originalFileName);
        kbFile.setFileType(contentType != null ? contentType.split(";")[0] : "unknown");
        kbFile.setFileSize(fileSize);
        kbFile.setFilePath(filePath.toString());
        kbFile.setVersionNo(previous == null ? 1 : (previous.getVersionNo() == null ? 1 : previous.getVersionNo() + 1));
        kbFile.setVersionGroupId(previous == null ? null : (previous.getVersionGroupId() == null ? previous.getId() : previous.getVersionGroupId()));
        kbFile.setParentVersionId(previous == null ? null : previous.getId());
        kbFile.setFileSha256(fileSha256);
        kbFile.setIsCurrent(1);
        kbFile.setQualityStatus("UNKNOWN");
        kbFile.setVectorStatus("PENDING");
        kbFile.setStatus("UPLOADED");
        kbFile.setProcessStage("UPLOADED");
        kbFile.setProgress(0);
        kbFile.setErrorMessage(null);
        kbFile.setProcessAttempts(0);
        kbFile.setNextRetryTime(null);
        //保存文件记录，后续处理线程只读取已提交的文件记录
        if (previous != null) {
            //同名新内容进入新版本，旧版本保留但不再参与默认检索
            fileMapper.clearCurrentVersion(kbFile.getVersionGroupId());
        }
        fileMapper.insert(kbFile);
        if (previous == null) {
            fileMapper.updateVersionGroup(kbFile.getId(), kbFile.getId());
            kbFile.setVersionGroupId(kbFile.getId());
        }
        fileMapper.insertVersion(kbFile.getId(), kbFile.getVersionNo(), kbFile.getFileSha256(),
                kbFile.getFilePath(), 1, null);
        //提交后台异步任务
        submitProcessTask(kbFile);
        return kbFile;
    }

    /**
     * 提交后台处理任务，事务提交后才执行
     */
    private void submitProcessTask(KbFile kbFile) {
        //todo 如果当前有数据库事务在运行，就等事务成功提交后，再异步处理这个文件；如果没事务，就立刻处理
        //检查当前是否在 Spring 事务中
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            //注册一个事务同步回调（监听事务的生命周期）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    //重写 afterCommit 方法：事务成功提交后自动调用
                    fileProcessExecutor.execute(() -> processFile(kbFile));
                }
            });
            return;
        }
        fileProcessExecutor.execute(() -> processFile(kbFile));
    }

    /**
     * 解析文件并写入文本切片和向量库
     */
    private void processFile(KbFile kbFile) {
        try {
            //记录本次处理尝试次数，失败后由定时任务按次数和时间自动重试
            int processAttempts = kbFile.getProcessAttempts() == null ? 0 : kbFile.getProcessAttempts();
            kbFile.setProcessAttempts(processAttempts + 1);
            // 第1步：进入文档解析阶段
            kbFile.setStatus("PROCESSING");
            kbFile.setProcessStage("PARSING");
            kbFile.setProgress(20);
            kbFile.setErrorMessage(null);
            fileMapper.updateProcessInfo(kbFile.getId(), kbFile.getStatus(), kbFile.getProcessStage(),
                    kbFile.getProgress(), kbFile.getErrorMessage());
            File file = new File(kbFile.getFilePath());
            //提取文件内容为字符串
            String content = documentParser.parse(file);
            log.info("Parsed document, content length: {}", content.length());
            // 第2步：进入文本切片阶段
            kbFile.setProcessStage("SPLITTING");
            kbFile.setProgress(40);
            fileMapper.updateProcessInfo(kbFile.getId(), kbFile.getStatus(), kbFile.getProcessStage(),
                    kbFile.getProgress(), null);
            //按自适应策略分割文本
            // 按知识库独立策略切片，未配置时继续使用系统默认值
            com.rag.entity.KnowledgeBase strategy = knowledgeBaseMapper.findById(kbFile.getKbId());
            int configuredChunkSize = strategy != null && strategy.getChunkSize() != null ? strategy.getChunkSize() : 500;
            int configuredOverlap = strategy != null && strategy.getChunkOverlap() != null ? strategy.getChunkOverlap() : 100;
            boolean headingSplit = strategy != null && Integer.valueOf(1).equals(strategy.getHeadingSplitEnabled());
            List<String> chunkTexts = textSplitter.split(content, configuredChunkSize, configuredOverlap, headingSplit);
            log.info("Split into {} chunks", chunkTexts.size());
            //存储文件拆分后的文本片段
            List<KbChunk> chunkBuffer = buildChunkBuffer(kbFile, chunkTexts);
            saveChunksBatch(chunkBuffer);
            List<KbChunk> savedChunks = chunkMapper.findByFileId(kbFile.getId());
            // 第3步：进入向量化和向量入库阶段
            kbFile.setProcessStage("VECTORIZING");
            kbFile.setProgress(60);
            fileMapper.updateProcessInfo(kbFile.getId(), kbFile.getStatus(), kbFile.getProcessStage(),
                    kbFile.getProgress(), null);
            //批量插入向量库
            saveVectorsBatch(kbFile, savedChunks);
            kbFile.setVectorStatus("READY");
            fileMapper.updateQuality(kbFile.getId(), "PASSED", java.math.BigDecimal.valueOf(100), "READY");
            // 第4步：所有文本切片和向量成功入库后标记完成
            kbFile.setStatus("COMPLETED");
            kbFile.setProcessStage("COMPLETED");
            kbFile.setProgress(100);
            kbFile.setErrorMessage(null);
            kbFile.setNextRetryTime(null);
            fileMapper.updateProcessInfo(kbFile.getId(), kbFile.getStatus(), kbFile.getProcessStage(),
                    kbFile.getProgress(), kbFile.getErrorMessage());
            log.info("File processed successfully: {}", kbFile.getFileName());
        } catch (Exception e) {
            log.error("Failed to process file", e);
            // 第5步：保留失败时已完成的进度，并记录可展示的失败原因
            kbFile.setStatus("FAILED");
            kbFile.setProcessStage("FAILED");
            kbFile.setErrorMessage(e.getMessage() == null ? "文件处理失败" : e.getMessage());
            int attempts = kbFile.getProcessAttempts() == null ? 1 : kbFile.getProcessAttempts();
            LocalDateTime nextRetryTime = attempts < maxProcessAttempts
                    ? LocalDateTime.now().plusSeconds(retryDelaySeconds * attempts)
                    : null;
            kbFile.setNextRetryTime(nextRetryTime);
            fileMapper.updateProcessFailure(kbFile.getId(), kbFile.getProcessStage(), kbFile.getProgress(),
                    kbFile.getErrorMessage(), attempts, nextRetryTime);
        }
    }

    /**
     * 定时提交到期的失败文件，避免外部模型短暂不可用导致人工介入
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${file.process.retry-interval-ms:60000}")
    public void retryFailedFiles() {
        List<KbFile> retryableFiles = fileMapper.findRetryableFiles(LocalDateTime.now(), maxProcessAttempts, 20);
        for (KbFile file : retryableFiles) {
            if (fileMapper.claimRetry(file.getId()) == 1) {
                fileProcessExecutor.execute(() -> processFile(file));
            }
        }
    }

    private List<KbChunk> buildChunkBuffer(KbFile kbFile, List<String> chunkTexts) {
        List<KbChunk> chunkBuffer = new ArrayList<>();
        for (int i = 0; i < chunkTexts.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setFileId(kbFile.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(chunkTexts.get(i));
            // 记录来源信息：文件名 + 段落序号（用于引用溯源）
            chunk.setSourceInfo(kbFile.getFileName() + ", 第" + (i + 1) + "段");
            chunkBuffer.add(chunk);
        }
        return chunkBuffer;
    }

    private void saveChunksBatch(List<KbChunk> chunkBuffer) {
        if (chunkBuffer.isEmpty()) {
            return;
        }
        chunkMapper.insertBatch(chunkBuffer);
    }

    private void saveVectorsBatch(KbFile kbFile, List<KbChunk> savedChunks) {
        List<QdrantService.VectorPoint> vectorPoints = new ArrayList<>();
        for (KbChunk chunk : savedChunks) {
            //向量化
            addVectorPoint(vectorPoints, kbFile, chunk);
            //判断是否达到批量阈值，达到就批量插入向量库
            flushVectorPointsIfNeeded(vectorPoints);
        }
        flushVectorPoints(vectorPoints);
    }

    private void addVectorPoint(List<QdrantService.VectorPoint> vectorPoints, KbFile kbFile, KbChunk chunk) {
        // 第1步：生成文本切片向量，失败时中止本次文件处理
        String chunkText = chunk.getContent();
        float[] embedding = embeddingService.embed(chunkText);
        // 第2步：准备向量检索需要的来源字段
        Map<String, Object> payload = new HashMap<>();
        payload.put("kb_id", kbFile.getKbId());
        payload.put("file_id", kbFile.getId());
        payload.put("chunk_id", chunk.getId());
        payload.put("content", chunkText);
        vectorPoints.add(new QdrantService.VectorPoint(chunk.getId(), embedding, payload));
    }

    private void flushVectorPointsIfNeeded(List<QdrantService.VectorPoint> vectorPoints) {
        // 检查条件：当前集合中的点数 < 批量阈值
        if (vectorPoints.size() < qdrantUpsertBatchSize) {
            return;// 未达标，什么都不做，直接返回
        }
        // 达标了，执行批量写入
        flushVectorPoints(vectorPoints);
    }

    private void flushVectorPoints(List<QdrantService.VectorPoint> vectorPoints) {
        if (vectorPoints.isEmpty()) {
            return;
        }
        // 第1步：复制当前批次并写入向量库
        List<QdrantService.VectorPoint> currentBatch = new ArrayList<>(vectorPoints);
        qdrantService.upsertBatch(currentBatch);
        // 第2步：写入成功后清空缓冲区
        vectorPoints.clear();
    }

    /**
     * 查询知识库下的所有文件
     */
    public List<KbFile> getByKbId(Long kbId) {
        return fileMapper.findCurrentByKbId(kbId);
    }

    /** 查询文件的全部历史版本 */
    public List<KbFile> getVersions(Long id) {
        KbFile file = getById(id);
        Long groupId = file.getVersionGroupId() == null ? file.getId() : file.getVersionGroupId();
        return fileMapper.findVersions(groupId);
    }

    /** 回滚到指定历史版本并重新建立其向量 */
    @Transactional
    public KbFile rollback(Long id) {
        KbFile target = getById(id);
        Long groupId = target.getVersionGroupId() == null ? target.getId() : target.getVersionGroupId();
        fileMapper.setCurrentVersion(target.getId(), groupId);
        //旧版本通常已有向量，只有缺失时才重建，避免无谓删除和重复调用模型
        if (!"READY".equalsIgnoreCase(target.getVectorStatus()) && "COMPLETED".equalsIgnoreCase(target.getStatus())) {
            reprocess(id);
        }
        return fileMapper.findById(id);
    }

    /** 更新文件目录和分类 */
    public void updateCatalog(Long id, Long folderId, String category) {
        getById(id);
        fileMapper.updateCatalog(id, folderId, category == null ? null : category.trim());
    }

    /** 计算文件 SHA-256，用于去重和备份校验 */
    private String calculateSha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception e) {
            throw new BusinessException("无法计算文件摘要");
        }
    }

    /**
     * 根据 ID 查询文件
     */
    public KbFile getById(Long id) {
        KbFile file = fileMapper.findById(id);
        if (file == null) {
            throw new BusinessException(404, "File not found");
        }
        return file;
    }

    /**
     * 重新处理文件
     * 清除旧的切片和向量后，重新解析、切片、向量化
     * 用于文件内容更新或处理失败后的重试
     */
    public void reprocess(Long id) {
        KbFile file = fileMapper.findById(id);
        if (file == null) {
            throw new BusinessException(404, "File not found");
        }
        // 清除旧的向量和切片
        qdrantService.deleteByFileId(id);
        chunkMapper.deleteByFileId(id);
        // 重新走处理流程
        file.setStatus("UPLOADED");
        file.setProcessStage("UPLOADED");
        file.setProgress(0);
        file.setErrorMessage(null);
        file.setProcessAttempts(0);
        file.setNextRetryTime(null);
        fileMapper.resetProcessRetry(id);
        fileMapper.updateProcessInfo(id, file.getStatus(), file.getProcessStage(), file.getProgress(), null);
        submitProcessTask(file);
    }

    /** 校验文件归属后重新处理，供任务中心调用 */
    public void reprocessOwned(Long id, Long userId) {
        KbFile file = getById(id);
        com.rag.entity.KnowledgeBase kb = knowledgeBaseMapper.findById(file.getKbId());
        if (kb == null || !kb.getCreateUser().equals(userId)) {
            throw new BusinessException(403, "无权重新处理该文件");
        }
        reprocess(id);
    }

    /**
     * 删除文件及其切片和向量
     */
    @Transactional
    public void delete(Long id) {
        KbFile file = fileMapper.findById(id);
        if (file == null) {
            throw new BusinessException(404, "File not found");
        }
        qdrantService.deleteByFileId(id);
        chunkMapper.deleteByFileId(id);
        try {
            Files.deleteIfExists(Paths.get(file.getFilePath()));
        } catch (IOException e) {
            log.error("Failed to delete file from disk", e);
        }
        fileMapper.deleteById(id);
    }
}
