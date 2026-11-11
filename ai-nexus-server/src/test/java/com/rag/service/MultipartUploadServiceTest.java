package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.KbFile;
import com.rag.entity.MultipartUploadChunk;
import com.rag.entity.MultipartUploadSession;
import com.rag.mapper.MultipartUploadChunkMapper;
import com.rag.mapper.MultipartUploadSessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分片上传服务测试
 * 验证上传会话、分片幂等、缺片校验和完整文件合并行为
 */
class MultipartUploadServiceTest {

    @TempDir
    Path tempDir;

    /**
     * 测试分片上传默认参数能够保存为确认的大小
     */
    @Test
    void multipartDefaultsShouldUseTwoGbAndTenMb() {
        MultipartUploadService service = new MultipartUploadService();
        ReflectionTestUtils.setField(service, "maxFileSize", 2L * 1024 * 1024 * 1024);
        ReflectionTestUtils.setField(service, "chunkSize", 10L * 1024 * 1024);

        assertEquals(2L * 1024 * 1024 * 1024, ReflectionTestUtils.getField(service, "maxFileSize"));
        assertEquals(10L * 1024 * 1024, ReflectionTestUtils.getField(service, "chunkSize"));
    }

    /**
     * 测试缺少分片时不能合并文件
     */
    @Test
    void completeShouldRejectMissingChunk() {
        MultipartUploadSessionMapper sessionMapper = mock(MultipartUploadSessionMapper.class);
        MultipartUploadChunkMapper chunkMapper = mock(MultipartUploadChunkMapper.class);
        MultipartUploadSession session = buildSession(2);
        when(sessionMapper.findByUploadId("upload-1")).thenReturn(session);
        when(chunkMapper.findByUploadId("upload-1")).thenReturn(List.of(buildChunk(0)));

        MultipartUploadService service = buildService(sessionMapper, chunkMapper, mock(FileService.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete("upload-1", 7L, null));

        assertEquals(409, exception.getCode());
    }

    /**
     * 测试同一个分片重复上传时只保留一条分片记录
     */
    @Test
    void sameChunkShouldBeIdempotent() throws Exception {
        MultipartUploadSessionMapper sessionMapper = mock(MultipartUploadSessionMapper.class);
        MultipartUploadChunkMapper chunkMapper = mock(MultipartUploadChunkMapper.class);
        MultipartUploadSession session = buildSession(1);
        when(sessionMapper.findByUploadId("upload-1")).thenReturn(session);
        when(chunkMapper.findByUploadIdAndIndex("upload-1", 0)).thenReturn(null);

        MultipartUploadService service = buildService(sessionMapper, chunkMapper, mock(FileService.class));
        String hash = service.sha256("chunk".getBytes(StandardCharsets.UTF_8));

        service.uploadChunk("upload-1", 7L, 0,
                new ByteArrayInputStream("chunk".getBytes(StandardCharsets.UTF_8)), 5L, hash);
        MultipartUploadChunk existingChunk = buildChunk(0);
        existingChunk.setChunkSha256(hash);
        when(chunkMapper.findByUploadIdAndIndex("upload-1", 0)).thenReturn(existingChunk);
        service.uploadChunk("upload-1", 7L, 0,
                new ByteArrayInputStream("chunk".getBytes(StandardCharsets.UTF_8)), 5L, hash);

        verify(chunkMapper).insert(any(MultipartUploadChunk.class));
        verify(chunkMapper, never()).deleteByUploadIdAndIndex("upload-1", 0);
    }

    /**
     * 测试完整分片合并后才登记知识库文件
     */
    @Test
    void completeShouldCreateFileOnlyAfterSha256Matches() throws Exception {
        MultipartUploadSessionMapper sessionMapper = mock(MultipartUploadSessionMapper.class);
        MultipartUploadChunkMapper chunkMapper = mock(MultipartUploadChunkMapper.class);
        FileService fileService = mock(FileService.class);
        MultipartUploadSession session = buildSession(2);
        KbFile expectedFile = new KbFile();
        expectedFile.setKbId(3L);
        when(sessionMapper.findByUploadId("upload-1")).thenReturn(session);
        when(sessionMapper.markMerging("upload-1")).thenReturn(1);
        MultipartUploadChunk secondChunk = buildChunk(1);
        secondChunk.setChunkSize(6L);
        when(chunkMapper.findByUploadId("upload-1")).thenReturn(List.of(buildChunk(0), secondChunk));
        when(fileService.registerSavedFile(eq(3L), eq("manual.txt"), eq("text/plain"), eq(11L), any(Path.class)))
                .thenReturn(expectedFile);

        MultipartUploadService service = buildService(sessionMapper, chunkMapper, fileService);
        Path partDir = tempDir.resolve(".parts").resolve("upload-1");
        java.nio.file.Files.createDirectories(partDir);
        java.nio.file.Files.writeString(partDir.resolve("0.part"), "hello");
        java.nio.file.Files.writeString(partDir.resolve("1.part"), " world");
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        String fullHash = service.sha256("hello world".getBytes(StandardCharsets.UTF_8));

        KbFile result = service.complete("upload-1", 7L, fullHash);

        verify(fileService).registerSavedFile(eq(3L), eq("manual.txt"), eq("text/plain"), eq(11L), any(Path.class));
        assertEquals("COMPLETED", session.getStatus());
        assertEquals(3L, result.getKbId());
    }

    /**
     * 测试已有其他请求占用合并状态时不能重复创建文件
     */
    @Test
    void completeShouldRejectConcurrentMerge() {
        MultipartUploadSessionMapper sessionMapper = mock(MultipartUploadSessionMapper.class);
        MultipartUploadChunkMapper chunkMapper = mock(MultipartUploadChunkMapper.class);
        MultipartUploadSession session = buildSession(1);
        session.setFileSize(5L);
        when(sessionMapper.findByUploadId("upload-1")).thenReturn(session);
        when(chunkMapper.findByUploadId("upload-1")).thenReturn(List.of(buildChunk(0)));
        when(sessionMapper.markMerging("upload-1")).thenReturn(0);

        MultipartUploadService service = buildService(sessionMapper, chunkMapper, mock(FileService.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete("upload-1", 7L, null));

        assertEquals(409, exception.getCode());
    }

    /**
     * 构造上传服务测试对象
     */
    private MultipartUploadService buildService(MultipartUploadSessionMapper sessionMapper,
                                                 MultipartUploadChunkMapper chunkMapper,
                                                 FileService fileService) {
        MultipartUploadService service = new MultipartUploadService();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseService", mock(KnowledgeBaseService.class));
        ReflectionTestUtils.setField(service, "fileService", fileService);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "chunkSize", 5L);
        ReflectionTestUtils.setField(service, "maxFileSize", 2L * 1024 * 1024 * 1024);
        return service;
    }

    /**
     * 构造测试上传会话
     */
    private MultipartUploadSession buildSession(int totalChunks) {
        MultipartUploadSession session = new MultipartUploadSession();
        session.setUploadId("upload-1");
        session.setKbId(3L);
        session.setCreateUser(7L);
        session.setFileName("manual.txt");
        session.setFileType("text/plain");
        session.setFileSize(11L);
        session.setChunkSize(5L);
        session.setTotalChunks(totalChunks);
        session.setStatus("UPLOADING");
        return session;
    }

    /**
     * 构造测试分片记录
     */
    private MultipartUploadChunk buildChunk(int index) {
        MultipartUploadChunk chunk = new MultipartUploadChunk();
        chunk.setUploadId("upload-1");
        chunk.setChunkIndex(index);
        chunk.setChunkSize(5L);
        chunk.setFilePath(tempDir.resolve(".parts/upload-1/" + index + ".part").toString());
        return chunk;
    }
}
