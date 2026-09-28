package com.rag.service;

import com.rag.ai.EmbeddingService;
import com.rag.catalog.CatalogMapper;
import com.rag.common.BusinessException;
import com.rag.entity.KbFile;
import com.rag.entity.KnowledgeBase;
import com.rag.entity.MultipartUploadSession;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.FileMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.mapper.MultipartUploadChunkMapper;
import com.rag.mapper.MultipartUploadSessionMapper;
import com.rag.eval.EvalMapper;
import com.rag.agent.mapper.AgentRunMapper;
import com.rag.rag.DocumentParser;
import com.rag.rag.QdrantService;
import com.rag.rag.RetrievalCache;
import com.rag.rag.TextSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件服务测试
 * 验证文件处理阶段、进度和失败原因能够被完整记录
 */
class FileServiceTest {

    @TempDir
    Path tempDir;

    /**
     * 测试文档解析失败时保存处理阶段、进度和失败原因
     */
    @Test
    void uploadShouldRecordParseFailureDetails() throws IOException {
        // 第1步：准备文件处理依赖和立即执行的后台线程
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        DocumentParser documentParser = mock(DocumentParser.class);
        TextSplitter textSplitter = mock(TextSplitter.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        QdrantService qdrantService = mock(QdrantService.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        Executor directExecutor = Runnable::run;
        doAnswer(invocation -> {
            KbFile file = invocation.getArgument(0);
            file.setId(12L);
            return 1;
        }).when(fileMapper).insert(any(KbFile.class));
        when(documentParser.parse(any(File.class))).thenThrow(new IOException("测试解析失败"));

        // 第2步：组装文件服务并设置临时上传目录
        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);
        ReflectionTestUtils.setField(service, "documentParser", documentParser);
        ReflectionTestUtils.setField(service, "textSplitter", textSplitter);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "fileProcessExecutor", directExecutor);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "qdrantUpsertBatchSize", 100);

        // 第3步：上传一个会触发解析失败的文本文件
        MockMultipartFile uploadFile = new MockMultipartFile(
                "file", "broken.txt", "text/plain", "测试内容".getBytes());
        KbFile result = service.upload(3L, uploadFile);

        // 第4步：确认文件进入解析阶段后记录明确的失败信息
        assertEquals("FAILED", result.getStatus());
        assertEquals("FAILED", result.getProcessStage());
        assertEquals(20, result.getProgress());
        assertEquals("测试解析失败", result.getErrorMessage());
        verify(fileMapper).updateProcessInfo(12L, "PROCESSING", "PARSING", 20, null);
        verify(fileMapper).updateProcessFailure(eq(12L), eq("FAILED"), eq(20), eq("测试解析失败"),
                eq(1), any());
    }

    /**
     * 测试向量删除失败时不继续删除数据库文件记录
     */
    @Test
    void deleteShouldKeepDatabaseRecordWhenVectorDeleteFails() throws IOException {
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        KbFile file = new KbFile();
        file.setId(15L);
        file.setKbId(3L);
        file.setFilePath(tempDir.resolve("keep.txt").toString());
        when(fileMapper.findById(15L)).thenReturn(file);
        org.mockito.Mockito.doThrow(new RuntimeException("qdrant unavailable"))
                .when(qdrantService).deleteByFileId(15L);

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);

        assertThrows(RuntimeException.class, () -> service.delete(15L));
        verify(fileMapper, never()).deleteById(15L);
        verify(chunkMapper, never()).deleteByFileId(15L);
    }

    /**
     * 测试到期失败文件会先抢占执行权，再提交到处理线程池
     */
    @Test
    void retryFailedFilesShouldClaimAndSubmitTask() {
        FileMapper fileMapper = mock(FileMapper.class);
        KbFile file = new KbFile();
        file.setId(21L);
        when(fileMapper.findRetryableFiles(any(), eq(3), eq(20))).thenReturn(List.of(file));
        when(fileMapper.claimRetry(21L)).thenReturn(1);
        java.util.concurrent.atomic.AtomicReference<Runnable> submitted = new java.util.concurrent.atomic.AtomicReference<>();

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        Executor capturingExecutor = submitted::set;
        ReflectionTestUtils.setField(service, "fileProcessExecutor", capturingExecutor);
        ReflectionTestUtils.setField(service, "maxProcessAttempts", 3);

        service.retryFailedFiles();

        verify(fileMapper).claimRetry(21L);
        org.junit.jupiter.api.Assertions.assertNotNull(submitted.get());
    }

    /**
     * 测试删除文件后立即失效该知识库的检索缓存
     */
    @Test
    void deleteShouldInvalidateRetrievalCache() throws IOException {
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KbFile file = new KbFile();
        file.setId(15L);
        file.setKbId(3L);
        file.setFilePath(tempDir.resolve("gone.txt").toString());
        when(fileMapper.findById(15L)).thenReturn(file);

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "catalogMapper", catalogMapper);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);

        service.delete(15L);

        verify(retrievalCache).invalidateKb(3L);
    }

    /**
     * 测试重新处理文件时缓存立即失效，避免重建期间命中已删除切片
     */
    @Test
    void reprocessShouldInvalidateRetrievalCacheImmediately() {
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KbFile file = new KbFile();
        file.setId(15L);
        file.setKbId(3L);
        when(fileMapper.findById(15L)).thenReturn(file);

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);
        //直接丢弃提交的后台任务，聚焦缓存失效行为
        ReflectionTestUtils.setField(service, "fileProcessExecutor", (Executor) task -> { });

        service.reprocess(15L);

        verify(retrievalCache).invalidateKb(3L);
    }

    /**
     * 测试版本回滚后失效检索缓存，旧版本切片不再作为当前内容返回
     */
    @Test
    void rollbackShouldInvalidateRetrievalCache() {
        FileMapper fileMapper = mock(FileMapper.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KbFile file = new KbFile();
        file.setId(15L);
        file.setKbId(3L);
        file.setVersionGroupId(15L);
        file.setVectorStatus("READY");
        file.setStatus("COMPLETED");
        when(fileMapper.findById(15L)).thenReturn(file);

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);

        service.rollback(15L);

        verify(fileMapper).setCurrentVersion(15L, 15L);
        verify(retrievalCache).invalidateKb(3L);
    }

    /**
     * 测试知识库级重建向量：仅创建者可操作，且逐文件提交重建
     */
    @Test
    void reprocessKbShouldReprocessCurrentFilesForOwner() {
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(3L);
        kb.setCreateUser(1L);
        when(knowledgeBaseMapper.findById(3L)).thenReturn(kb);
        KbFile first = buildFile(11L, 3L);
        KbFile second = buildFile(12L, 3L);
        when(fileMapper.findCurrentByKbId(3L)).thenReturn(List.of(first, second));
        when(fileMapper.findById(11L)).thenReturn(first);
        when(fileMapper.findById(12L)).thenReturn(second);

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);
        ReflectionTestUtils.setField(service, "fileProcessExecutor", (Executor) task -> { });

        int submitted = service.reprocessKb(3L, 1L);

        assertEquals(2, submitted);
        verify(qdrantService).deleteByFileId(11L);
        verify(qdrantService).deleteByFileId(12L);

        //非创建者必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.reprocessKb(3L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 全局重建期间单库重建必须被拒绝，避免两个任务同时替换 collection
     */
    @Test
    void reprocessKbShouldRejectWhenQdrantRebuilding() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(3L);
        kb.setCreateUser(1L);
        when(knowledgeBaseMapper.findById(3L)).thenReturn(kb);
        when(qdrantService.isRebuilding()).thenReturn(true);

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.reprocessKb(3L, 1L));
        assertEquals(409, exception.getCode());
    }

    /**
     * 测试删除知识库时清理向量、切片、文件、版本、标签和分片上传全部关联数据
     */
    @Test
    void deleteKbDataShouldCleanAllRelations() {
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        MultipartUploadChunkMapper multipartUploadChunkMapper = mock(MultipartUploadChunkMapper.class);
        MultipartUploadSessionMapper multipartUploadSessionMapper = mock(MultipartUploadSessionMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KbFile file = buildFile(11L, 3L);
        file.setFilePath(tempDir.resolve("missing.txt").toString());
        when(fileMapper.findByKbId(3L)).thenReturn(List.of(file));

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "catalogMapper", catalogMapper);
        ReflectionTestUtils.setField(service, "multipartUploadChunkMapper", multipartUploadChunkMapper);
        ReflectionTestUtils.setField(service, "multipartUploadSessionMapper", multipartUploadSessionMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);

        service.deleteKbData(3L);

        verify(qdrantService).deleteByKbId(3L);
        verify(chunkMapper).deleteByKbId(3L);
        verify(fileMapper).deleteVersionsByKbId(3L);
        verify(catalogMapper).deleteTagRelsByKbId(3L);
        verify(catalogMapper).deleteTagsByKbId(3L);
        verify(catalogMapper).deleteFoldersByKbId(3L);
        verify(fileMapper).deleteByKbId(3L);
        verify(multipartUploadChunkMapper).deleteByKbId(3L);
        verify(multipartUploadSessionMapper).deleteByKbId(3L);
        verify(retrievalCache).invalidateKb(3L);
    }

    /**
     * 删除标记存在时后台处理必须在写入切片前退出
     */
    @Test
    void processFileShouldStopWhenKnowledgeBaseIsBeingDeleted() throws Exception {
        FileMapper fileMapper = mock(FileMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        DocumentParser documentParser = mock(DocumentParser.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KbFile file = buildFile(11L, 3L);
        file.setFilePath(tempDir.resolve("source.txt").toString());
        Files.writeString(Path.of(file.getFilePath()), "content");
        when(knowledgeBaseMapper.findById(3L)).thenReturn(null);
        when(documentParser.parse(any(File.class))).thenReturn("content");

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "documentParser", documentParser);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);
        ReflectionTestUtils.invokeMethod(service, "processFile", file);

        verify(chunkMapper, never()).insertBatch(any());
        verify(qdrantService, never()).upsertBatch(any());
    }

    /**
     * 删除知识库时应清理评测、智能体记录和分片临时目录
     */
    @Test
    void deleteKbDataShouldCleanEvaluationAgentAndPartDirectory() throws Exception {
        FileMapper fileMapper = mock(FileMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        MultipartUploadChunkMapper uploadChunkMapper = mock(MultipartUploadChunkMapper.class);
        MultipartUploadSessionMapper uploadSessionMapper = mock(MultipartUploadSessionMapper.class);
        EvalMapper evalMapper = mock(EvalMapper.class);
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        QdrantService qdrantService = mock(QdrantService.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        MultipartUploadSession session = new MultipartUploadSession();
        session.setUploadId("upload-1");
        when(uploadSessionMapper.findByKbId(3L)).thenReturn(List.of(session));
        when(fileMapper.findByKbId(3L)).thenReturn(List.of());

        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "catalogMapper", catalogMapper);
        ReflectionTestUtils.setField(service, "multipartUploadChunkMapper", uploadChunkMapper);
        ReflectionTestUtils.setField(service, "multipartUploadSessionMapper", uploadSessionMapper);
        ReflectionTestUtils.setField(service, "evalMapper", evalMapper);
        ReflectionTestUtils.setField(service, "agentRunMapper", agentRunMapper);
        ReflectionTestUtils.setField(service, "qdrantService", qdrantService);
        ReflectionTestUtils.setField(service, "retrievalCache", retrievalCache);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        Path partDirectory = tempDir.resolve(".parts").resolve("upload-1");
        Files.createDirectories(partDirectory);
        Files.writeString(partDirectory.resolve("part"), "data");

        service.deleteKbData(3L);

        verify(evalMapper).deleteRunItemsByKbId(3L);
        verify(evalMapper).deleteRunsByKbId(3L);
        verify(evalMapper).deleteCasesByKbId(3L);
        verify(agentRunMapper).deleteByKbId(3L);
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(partDirectory));
    }

    /** 构造测试文件 */
    private KbFile buildFile(Long id, Long kbId) {
        KbFile file = new KbFile();
        file.setId(id);
        file.setKbId(kbId);
        return file;
    }
}
