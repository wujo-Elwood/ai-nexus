package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.dto.MultipartUploadInitRequest;
import com.rag.dto.MultipartUploadInitResponse;
import com.rag.dto.MultipartUploadStatusResponse;
import com.rag.entity.KbFile;
import com.rag.entity.MultipartUploadChunk;
import com.rag.entity.MultipartUploadSession;
import com.rag.mapper.MultipartUploadChunkMapper;
import com.rag.mapper.MultipartUploadSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘大文件分片上传服务
 * 负责上传会话、分片落盘、断点恢复、合并校验和过期清理
 */
@Slf4j
@Service
public class MultipartUploadService {

    /** 允许上传的文件类型，与普通上传保持一致 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/markdown",
            "text/x-markdown"
    );

    /** 允许上传的文件扩展名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".doc", ".docx", ".xlsx", ".txt", ".md");

    @Autowired
    private MultipartUploadSessionMapper sessionMapper;

    @Autowired
    private MultipartUploadChunkMapper chunkMapper;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private FileService fileService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.multipart.max-file-size:2147483648}")
    private long maxFileSize;

    @Value("${file.multipart.chunk-size:10485760}")
    private long chunkSize;

    @Value("${file.multipart.expire-hours:24}")
    private long expireHours;

    /**
     * 初始化一个分片上传会话
     *
     * @param userId 当前用户编号
     * @param request 初始化参数
     * @return 上传会话编号和分片信息
     */
    @Transactional
    public MultipartUploadInitResponse init(Long userId, MultipartUploadInitRequest request) {
        knowledgeBaseService.checkManageAccess(request.getKbId(), userId);
        validateFile(request.getFileName(), request.getFileType(), request.getFileSize());

        String uploadId = UUID.randomUUID().toString().replace("-", "");
        int totalChunks = (int) ((request.getFileSize() + chunkSize - 1) / chunkSize);
        MultipartUploadSession session = new MultipartUploadSession();
        session.setUploadId(uploadId);
        session.setKbId(request.getKbId());
        session.setCreateUser(userId);
        session.setFileName(request.getFileName());
        session.setFileType(normalizeContentType(request.getFileType()));
        session.setFileSize(request.getFileSize());
        session.setFileSha256(normalizeHash(request.getFileSha256()));
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setUploadedChunks(0);
        session.setUploadedBytes(0L);
        session.setStatus("UPLOADING");
        session.setExpireTime(LocalDateTime.now().plusHours(expireHours));
        sessionMapper.insert(session);
        createPartDirectory(uploadId);

        MultipartUploadInitResponse response = new MultipartUploadInitResponse();
        response.setUploadId(uploadId);
        response.setChunkSize(chunkSize);
        response.setTotalChunks(totalChunks);
        response.setUploadedChunks(List.of());
        return response;
    }

    /**
     * 保存一个上传分片并更新上传进度
     *
     * @param uploadId 上传会话编号
     * @param chunkIndex 分片序号
     * @param inputStream 分片请求流
     * @param contentLength 请求声明的分片大小
     * @param expectedHash 前端提供的分片摘要
     */
    @Transactional
    public void uploadChunk(String uploadId, Long userId, int chunkIndex, InputStream inputStream,
                            long contentLength, String expectedHash) {
        MultipartUploadSession session = getSession(uploadId);
        knowledgeBaseService.checkManageAccess(session.getKbId(), userId);
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new BusinessException(409, "Upload session is not accepting chunks");
        }
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new BusinessException(400, "Invalid chunk index");
        }
        if (contentLength < 0 || contentLength > session.getChunkSize()
                || (chunkIndex < session.getTotalChunks() - 1 && contentLength != session.getChunkSize())) {
            throw new BusinessException(400, "Invalid chunk size");
        }

        synchronized (this) {
            MultipartUploadChunk existing = chunkMapper.findByUploadIdAndIndex(uploadId, chunkIndex);
            if (existing != null) {
                if (existing.getChunkSize() != contentLength
                        || !hashMatches(expectedHash, existing.getChunkSha256())) {
                    throw new BusinessException(409, "Chunk already exists with different content");
                }
                return;
            }

            Path partPath = getPartDirectory(uploadId).resolve(chunkIndex + ".part");
            try {
                //确保断点续传时临时目录存在，服务重启后也能继续写入分片
                createPartDirectory(uploadId);
                String actualHash = writeChunk(partPath, inputStream, contentLength);
                if (expectedHash != null && !hashMatches(expectedHash, actualHash)) {
                    Files.deleteIfExists(partPath);
                    throw new BusinessException(400, "Chunk checksum does not match");
                }
                MultipartUploadChunk chunk = new MultipartUploadChunk();
                chunk.setUploadId(uploadId);
                chunk.setChunkIndex(chunkIndex);
                chunk.setChunkSize(contentLength);
                chunk.setChunkSha256(actualHash);
                chunk.setFilePath(partPath.toString());
                chunkMapper.insert(chunk);
                List<MultipartUploadChunk> chunks = chunkMapper.findByUploadId(uploadId);
                long uploadedBytes = chunks.stream().mapToLong(MultipartUploadChunk::getChunkSize).sum();
                sessionMapper.updateProgress(uploadId, chunks.size(), uploadedBytes);
            } catch (IOException e) {
                throw new BusinessException("Failed to save upload chunk");
            }
        }
    }

    /**
     * 查询上传会话状态，用于断点恢复
     *
     * @param uploadId 上传会话编号
     * @return 当前上传进度
     */
    public MultipartUploadStatusResponse getStatus(String uploadId, Long userId) {
        MultipartUploadSession session = getSession(uploadId);
        knowledgeBaseService.checkManageAccess(session.getKbId(), userId);
        List<MultipartUploadChunk> chunks = chunkMapper.findByUploadId(uploadId);
        MultipartUploadStatusResponse response = new MultipartUploadStatusResponse();
        response.setUploadId(uploadId);
        response.setStatus(session.getStatus());
        response.setChunkSize(session.getChunkSize());
        response.setTotalChunks(session.getTotalChunks());
        response.setUploadedBytes(chunks.stream().mapToLong(MultipartUploadChunk::getChunkSize).sum());
        response.setUploadedChunks(chunks.stream().map(MultipartUploadChunk::getChunkIndex).sorted().toList());
        response.setErrorMessage(session.getErrorMessage());
        return response;
    }

    /**
     * 合并全部分片并登记知识库文件
     *
     * @param uploadId 上传会话编号
     * @param userId 当前用户编号
     * @param expectedHash 可选的完整文件摘要
     * @return 新建的知识库文件
     */
    @Transactional
    public KbFile complete(String uploadId, Long userId, String expectedHash) {
        MultipartUploadSession session = getSession(uploadId);
        knowledgeBaseService.checkManageAccess(session.getKbId(), userId);
        if (!"UPLOADING".equals(session.getStatus()) && !"FAILED".equals(session.getStatus())) {
            throw new BusinessException(409, "Upload session cannot be completed");
        }
        List<MultipartUploadChunk> chunks = chunkMapper.findByUploadId(uploadId);
        if (chunks.size() != session.getTotalChunks()) {
            throw new BusinessException(409, "Upload is missing chunks");
        }
        Set<Integer> indexes = new HashSet<>();
        for (MultipartUploadChunk chunk : chunks) {
            indexes.add(chunk.getChunkIndex());
        }
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!indexes.contains(i)) {
                throw new BusinessException(409, "Upload is missing chunks");
            }
        }

        //使用数据库条件更新抢占合并权，避免并发请求重复创建知识库文件
        if (sessionMapper.markMerging(uploadId) != 1) {
            throw new BusinessException(409, "Upload session is being merged");
        }
        session.setStatus("MERGING");
        Path mergedTemp = getPartDirectory(uploadId).resolve("merged.tmp");
        try {
            List<MultipartUploadChunk> orderedChunks = chunks.stream()
                    .sorted(Comparator.comparing(MultipartUploadChunk::getChunkIndex)).toList();
            long totalBytes = mergeChunks(orderedChunks, mergedTemp);
            if (totalBytes != session.getFileSize()) {
                throw new BusinessException(400, "Merged file size does not match");
            }
            String fullHash = normalizeHash(expectedHash);
            if (fullHash == null) {
                fullHash = normalizeHash(session.getFileSha256());
            }
            //始终计算合并文件摘要；前端提供摘要时再做一致性校验
            String mergedHash = sha256(mergedTemp);
            if (fullHash != null && !hashMatches(fullHash, mergedHash)) {
                throw new BusinessException(400, "Merged file checksum does not match");
            }

            Path finalPath = createFinalPath(session.getFileName());
            try {
                Files.move(mergedTemp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                //文件系统不支持原子移动时退回普通移动，仍然只在完整校验后落正式文件
                Files.move(mergedTemp, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            KbFile file = fileService.registerSavedFile(session.getKbId(), session.getFileName(),
                    session.getFileType(), totalBytes, finalPath);
            session.setStatus("COMPLETED");
            sessionMapper.updateStatus(uploadId, "COMPLETED", null);
            chunkMapper.deleteByUploadId(uploadId);
            deletePartDirectory(uploadId);
            return file;
        } catch (BusinessException e) {
            session.setStatus("FAILED");
            sessionMapper.updateStatus(uploadId, "FAILED", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to merge upload session {}", uploadId, e);
            session.setStatus("FAILED");
            sessionMapper.updateStatus(uploadId, "FAILED", "Failed to merge uploaded file");
            throw new BusinessException("Failed to merge uploaded file");
        }
    }

    /**
     * 取消上传会话并清理临时分片
     *
     * @param uploadId 上传会话编号
     * @param userId 当前用户编号
     */
    @Transactional
    public void cancel(String uploadId, Long userId) {
        MultipartUploadSession session = getSession(uploadId);
        knowledgeBaseService.checkManageAccess(session.getKbId(), userId);
        sessionMapper.updateStatus(uploadId, "CANCELLED", null);
        chunkMapper.deleteByUploadId(uploadId);
        sessionMapper.deleteByUploadId(uploadId);
        deletePartDirectory(uploadId);
    }

    /**
     * 定时清理过期上传会话和临时分片
     */
    @Scheduled(fixedDelayString = "${file.multipart.cleanup-interval-ms:3600000}")
    public void cleanupExpiredUploads() {
        List<MultipartUploadSession> sessions = sessionMapper.findExpired(LocalDateTime.now());
        for (MultipartUploadSession session : sessions) {
            chunkMapper.deleteByUploadId(session.getUploadId());
            sessionMapper.deleteByUploadId(session.getUploadId());
            deletePartDirectory(session.getUploadId());
        }
    }

    /**
     * 计算字节数组 SHA-256，供测试和分片校验复用
     */
    public String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * 查询上传会话并统一处理不存在错误
     */
    private MultipartUploadSession getSession(String uploadId) {
        MultipartUploadSession session = sessionMapper.findByUploadId(uploadId);
        if (session == null) {
            throw new BusinessException(404, "Upload session not found");
        }
        return session;
    }

    /**
     * 校验文件名、类型和大小
     */
    private void validateFile(String fileName, String fileType, Long fileSize) {
        if (fileSize == null || fileSize <= 0 || fileSize > maxFileSize) {
            throw new BusinessException(400, "File size must be between 1 byte and 2GB");
        }
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String extension = lowerName.contains(".") ? lowerName.substring(lowerName.lastIndexOf('.')) : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)
                && (fileType == null || !ALLOWED_TYPES.contains(fileType.split(";")[0]))) {
            throw new BusinessException(400, "Unsupported file type");
        }
    }

    /**
     * 规范化 MIME 类型
     */
    private String normalizeContentType(String fileType) {
        return fileType == null || fileType.isBlank() ? "application/octet-stream" : fileType.split(";")[0];
    }

    /**
     * 规范化摘要文本
     */
    private String normalizeHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        String normalized = hash.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new BusinessException(400, "Invalid SHA-256 value");
        }
        return normalized;
    }

    /**
     * 比较两个摘要是否一致
     */
    private boolean hashMatches(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    /**
     * 创建上传分片目录
     */
    private void createPartDirectory(String uploadId) {
        try {
            Files.createDirectories(getPartDirectory(uploadId));
        } catch (IOException e) {
            throw new BusinessException("Failed to create upload directory");
        }
    }

    /**
     * 获取上传会话临时目录
     */
    private Path getPartDirectory(String uploadId) {
        return Paths.get(uploadDir).resolve(".parts").resolve(uploadId).normalize();
    }

    /**
     * 创建正式文件路径
     */
    private Path createFinalPath(String originalName) throws IOException {
        Path directory = Paths.get(uploadDir).normalize();
        Files.createDirectories(directory);
        String safeName = originalName == null ? "file" : Paths.get(originalName).getFileName().toString();
        return directory.resolve(UUID.randomUUID() + "_" + safeName).normalize();
    }

    /**
     * 将请求流写入分片文件并计算摘要
     */
    private String writeChunk(Path partPath, InputStream inputStream, long expectedSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(partPath)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                if (total > expectedSize) {
                    throw new IOException("Chunk is larger than declared size");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total != expectedSize) {
            Files.deleteIfExists(partPath);
            throw new IOException("Chunk size does not match");
        }
        return bytesToHex(digest.digest());
    }

    /**
     * 按顺序合并所有分片
     */
    private long mergeChunks(List<MultipartUploadChunk> chunks, Path mergedPath) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(mergedPath)) {
            for (MultipartUploadChunk chunk : chunks) {
                Path partPath = Paths.get(chunk.getFilePath()).normalize();
                if (!partPath.startsWith(getPartDirectory(chunk.getUploadId()))) {
                    throw new IOException("Invalid chunk path");
                }
                if (!Files.isRegularFile(partPath) || Files.size(partPath) != chunk.getChunkSize()) {
                    throw new IOException("Chunk file size does not match");
                }
                try (InputStream input = Files.newInputStream(partPath)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        total += read;
                    }
                }
            }
        }
        return total;
    }

    /**
     * 计算文件摘要，使用流式读取避免大文件一次性进入内存
     */
    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /**
     * 将字节摘要转换为十六进制文本
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    /**
     * 清理上传会话临时目录
     */
    private void deletePartDirectory(String uploadId) {
        Path directory = getPartDirectory(uploadId);
        if (!directory.startsWith(Paths.get(uploadDir).resolve(".parts").normalize())) {
            return;
        }
        try {
            if (Files.notExists(directory)) {
                return;
            }
            try (var stream = Files.walk(directory)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete upload temporary file");
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Failed to clean upload directory {}", uploadId);
        }
    }

}
