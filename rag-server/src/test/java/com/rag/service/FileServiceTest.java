package com.rag.service;

import com.rag.ai.EmbeddingService;
import com.rag.entity.KbFile;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.FileMapper;
import com.rag.rag.DocumentParser;
import com.rag.rag.QdrantService;
import com.rag.rag.TextSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
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
}
