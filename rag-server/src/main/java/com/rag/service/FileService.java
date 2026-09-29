package com.rag.service;

import com.rag.ai.EmbeddingService;
import com.rag.catalog.CatalogMapper;
import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.KbFile;
import com.rag.entity.MultipartUploadSession;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.FileMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.mapper.MultipartUploadChunkMapper;
import com.rag.mapper.MultipartUploadSessionMapper;
import com.rag.rag.DocumentParser;
import com.rag.rag.QdrantService;
import com.rag.rag.RetrievalCache;
import com.rag.rag.TextSplitter;
import com.rag.rbac.service.RbacService;
import com.rag.eval.EvalMapper;
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
import java.util.stream.Stream;

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
    private RbacService rbacService;
    @Autowired
    private ChunkMapper chunkMapper;
    @Autowired
    private CatalogMapper catalogMapper;
    @Autowired
    private MultipartUploadChunkMapper multipartUploadChunkMapper;
    @Autowired
    private MultipartUploadSessionMapper multipartUploadSessionMapper;
    @Autowired
    private EvalMapper evalMapper;
    @Autowired
    private DocumentParser documentParser;
    @Autowired
    private TextSplitter textSplitter;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private QdrantService qdrantService;
    @Autowired
    private RetrievalCache retrievalCache;
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

    /** 全局向量重建状态快照，供管理界面显示进度 */
    private final Object rebuildStatusLock = new Object();
    private int rebuildTotalFiles;
    private int rebuildCompletedFiles;
    private int rebuildFailedFiles;
    private Long rebuildStartedBy;
    private LocalDateTime rebuildStartedAt;
    private LocalDateTime rebuildFinishedAt;
    private String rebuildError;

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
        //旧版本已降级为非当前版本，检索缓存立即失效
        invalidateRetrievalCache(kbId);
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
     * 失效指定知识库的检索缓存，事务中则延迟到提交后执行
     * 文件删除、重新处理或版本切换后，缓存中的 chunk ID 可能已删除或不再是当前版本
     */
    private void invalidateRetrievalCache(Long kbId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    retrievalCache.invalidateKb(kbId);
                }
            });
            return;
        }
        retrievalCache.invalidateKb(kbId);
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
            if (!isProcessingAllowed(kbFile)) {
                return;
            }
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
            if (!isProcessingAllowed(kbFile)) {
                chunkMapper.deleteByFileId(kbFile.getId());
                return;
            }
            List<KbChunk> savedChunks = chunkMapper.findByFileId(kbFile.getId());
            // 第3步：进入向量化和向量入库阶段
            kbFile.setProcessStage("VECTORIZING");
            kbFile.setProgress(60);
            fileMapper.updateProcessInfo(kbFile.getId(), kbFile.getStatus(), kbFile.getProcessStage(),
                    kbFile.getProgress(), null);
            //批量插入向量库
            if (!isProcessingAllowed(kbFile)) {
                chunkMapper.deleteByFileId(kbFile.getId());
                return;
            }
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
        } finally {
            //处理完成后切片已更换，下次检索必须重新召回
            retrievalCache.invalidateKb(kbFile.getKbId());
        }
    }

    /**
     * 校验异步处理期间知识库和文件仍然有效
     */
    private boolean isProcessingAllowed(KbFile file) {
        if (KnowledgeBaseService.isDeleting(file.getKbId())) {
            return false;
        }
        com.rag.entity.KnowledgeBase kb = knowledgeBaseMapper.findById(file.getKbId());
        KbFile current = fileMapper.findById(file.getId());
        return kb != null && current != null && (current.getIsCurrent() == null || current.getIsCurrent() == 1);
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
        //版本切换后缓存中的旧版本切片不再可信
        invalidateRetrievalCache(target.getKbId());
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
        //重建期间旧切片已删除，缓存必须立即失效
        retrievalCache.invalidateKb(file.getKbId());
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
     * 重建知识库全部当前版本文件的切片和向量
     * 用于更换 Embedding 模型后批量重建，逐文件异步执行，进度可在任务中心查看
     *
     * @return 提交重建的文件数量
     */
    public int reprocessKb(Long kbId, Long userId) {
        com.rag.entity.KnowledgeBase kb = knowledgeBaseMapper.findById(kbId);
        if (kb == null) {
            throw new BusinessException(404, "Knowledge base not found");
        }
        if (!kb.getCreateUser().equals(userId)) {
            throw new BusinessException(403, "无权重建该知识库的向量");
        }
        if (qdrantService.isRebuilding()) {
            throw new BusinessException(409, "全局向量正在重建，暂时无法提交知识库重建");
        }
        List<KbFile> files = fileMapper.findCurrentByKbId(kbId);
        for (KbFile file : files) {
            reprocess(file.getId());
        }
        return files.size();
    }

    /**
     * 管理员提交全部知识库的向量重建任务
     */
    public int rebuildAllVectors(Long userId) {
        if (!rbacService.hasRole(userId, "admin")) {
            throw new BusinessException(403, "仅管理员可以执行全局向量重建");
        }
        if (qdrantService.isRebuilding()) {
            throw new BusinessException(409, "全局向量正在重建，请勿重复提交");
        }
        qdrantService.startRebuild();
        List<KbFile> files;
        try {
            //进入重建状态后再读取文件快照，避免并发上传任务漏进本次重建
            files = fileMapper.findAllCurrent();
            synchronized (rebuildStatusLock) {
                rebuildTotalFiles = files.size();
                rebuildCompletedFiles = 0;
                rebuildFailedFiles = 0;
                rebuildStartedBy = userId;
                rebuildStartedAt = LocalDateTime.now();
                rebuildFinishedAt = null;
                rebuildError = null;
            }
            fileProcessExecutor.execute(() -> rebuildAllVectorsTask(files));
        } catch (RuntimeException e) {
            //线程池拒绝提交时立即释放重建状态，避免服务永久拒绝检索和写入
            synchronized (rebuildStatusLock) {
                rebuildError = e.getMessage() == null ? "无法提交重建任务" : e.getMessage();
                rebuildFinishedAt = LocalDateTime.now();
            }
            qdrantService.finishRebuild();
            throw new BusinessException(503, "无法提交全局向量重建任务，请稍后重试");
        }
        return files.size();
    }

    /**
     * 执行全部知识库向量重建任务
     */
    private void rebuildAllVectorsTask(List<KbFile> files) {
        qdrantService.beginRebuildWrites();
        try {
            //使用固定探测文本取得当前 Embedding 模型维度，空知识库也能完成 collection 迁移
            float[] probe = embeddingService.embed("qdrant vector rebuild dimension probe");
            qdrantService.recreateCollection(probe.length);
            chunkMapper.deleteByAllFiles();
            fileMapper.markAllVectorsPending();
            for (KbFile file : files) {
                if (knowledgeBaseMapper.findById(file.getKbId()) != null) {
                    processFile(file);
                    synchronized (rebuildStatusLock) {
                        if ("COMPLETED".equalsIgnoreCase(file.getStatus())) {
                            rebuildCompletedFiles++;
                        } else {
                            rebuildFailedFiles++;
                        }
                    }
                } else {
                    synchronized (rebuildStatusLock) {
                        rebuildCompletedFiles++;
                    }
                }
            }
        } catch (Exception e) {
            synchronized (rebuildStatusLock) {
                rebuildError = e.getMessage() == null ? "全局向量重建失败" : e.getMessage();
            }
            throw e;
        } finally {
            qdrantService.endRebuildWrites();
            qdrantService.finishRebuild();
            retrievalCache.invalidateAll();
            synchronized (rebuildStatusLock) {
                rebuildFinishedAt = LocalDateTime.now();
            }
        }
    }

    /** 查询全局向量重建状态，返回最近一次重建的进度快照 */
    public Map<String, Object> getRebuildStatus() {
        synchronized (rebuildStatusLock) {
            int total = rebuildTotalFiles;
            int completed = rebuildCompletedFiles;
            int failed = rebuildFailedFiles;
            int progress = total == 0 ? (qdrantService.isRebuilding() ? 0 : 100)
                    : Math.min(100, (completed + failed) * 100 / total);
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("rebuilding", qdrantService.isRebuilding());
            status.put("status", qdrantService.isRebuilding() ? "RUNNING" : (rebuildError == null ? "COMPLETED" : "FAILED"));
            status.put("totalFiles", total);
            status.put("completedFiles", completed);
            status.put("failedFiles", failed);
            status.put("progress", progress);
            status.put("startedBy", rebuildStartedBy);
            status.put("startedAt", rebuildStartedAt);
            status.put("finishedAt", rebuildFinishedAt);
            status.put("error", rebuildError);
            return status;
        }
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
        catalogMapper.clearFileTags(id);
        try {
            Files.deleteIfExists(Paths.get(file.getFilePath()));
        } catch (IOException e) {
            log.error("Failed to delete file from disk", e);
        }
        fileMapper.deleteById(id);
        invalidateRetrievalCache(file.getKbId());
    }

    /**
     * 清理知识库的全部关联数据：向量、切片、文件、版本、目录、标签和分片上传记录
     * 数据库无外键约束，删除知识库时必须显式清理，否则全部残留为孤儿数据
     */
    @Transactional
    public void deleteKbData(Long kbId) {
        // 第1步：一次性删除该库全部向量，失败时阻断数据库删除以避免孤立向量
        qdrantService.deleteByKbId(kbId);
        // 第2步：删除磁盘上的原始文件（含历史版本的物理文件）
        for (KbFile file : fileMapper.findByKbId(kbId)) {
            try {
                deletePathStrict(file.getFilePath());
            } catch (IOException e) {
                throw new BusinessException(500, "无法删除知识库文件，请检查磁盘权限后重试：" + file.getFilePath());
            }
        }
        //删除未完成分片目录和知识库备份文件，避免磁盘残留
        List<MultipartUploadSession> uploadSessions = multipartUploadSessionMapper.findByKbId(kbId);
        if (uploadSessions != null && uploadDir != null && !uploadDir.isBlank()) {
            for (MultipartUploadSession session : uploadSessions) {
                try {
                    deleteDirectoryStrict(Paths.get(uploadDir).resolve(".parts").resolve(session.getUploadId()));
                } catch (IOException e) {
                    throw new BusinessException(500, "无法删除上传临时文件，请检查磁盘权限后重试");
                }
            }
        }
        try {
            if (uploadDir != null && !uploadDir.isBlank()) {
                Path backupDirectory = Paths.get(uploadDir).resolve("backups");
                if (Files.exists(backupDirectory)) {
                    try (Stream<Path> paths = Files.list(backupDirectory)) {
                        paths.filter(path -> path.getFileName().toString().startsWith("kb-" + kbId + "-"))
                                .forEach(path -> {
                                    try {
                                        deletePathStrict(path.toString());
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                    }
                }
            }
        } catch (RuntimeException | IOException e) {
            throw new BusinessException(500, "无法删除知识库备份文件，请检查磁盘权限后重试");
        }
        // 第3步：删除数据库中的切片、版本、标签、目录、文件和分片上传记录
        if (evalMapper != null) {
            evalMapper.deleteRunItemsByKbId(kbId);
            evalMapper.deleteRunsByKbId(kbId);
            evalMapper.deleteCasesByKbId(kbId);
        }
        chunkMapper.deleteByKbId(kbId);
        fileMapper.deleteVersionsByKbId(kbId);
        catalogMapper.deleteTagRelsByKbId(kbId);
        catalogMapper.deleteTagsByKbId(kbId);
        catalogMapper.deleteFoldersByKbId(kbId);
        fileMapper.deleteByKbId(kbId);
        multipartUploadChunkMapper.deleteByKbId(kbId);
        multipartUploadSessionMapper.deleteByKbId(kbId);
        retrievalCache.invalidateKb(kbId);
    }

    /** 删除文件或目录，不存在时按幂等成功处理 */
    private void deletePathStrict(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return;
        }
        Path target = Paths.get(path);
        if (Files.notExists(target)) {
            return;
        }
        if (Files.isDirectory(target)) {
            deleteDirectoryStrict(target);
            return;
        }
        Files.delete(target);
    }

    /** 递归删除目录，任一文件失败都抛出异常 */
    private void deleteDirectoryStrict(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.delete(path);
            }
        }
    }
}
