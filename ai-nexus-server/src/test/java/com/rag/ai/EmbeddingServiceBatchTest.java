package com.rag.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量向量化测试
 *
 * 覆盖三件事：
 * 1. 配置成 Ollama 单条地址时，批量走 /api/embed 且顺序与输入一致；
 * 2. 批量接口返回 4xx 时自动回退逐条，且只回退一次（后续批次不再白试）；
 * 3. 上游返回条数不匹配时报错，避免向量被错配到别的文本块上。
 */
class EmbeddingServiceBatchTest {

    private static final String SINGLE_URL = "http://localhost:11434/api/embeddings";
    private static final String BATCH_URL = "http://localhost:11434/api/embed";

    private EmbeddingService embeddingService;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        embeddingService = new EmbeddingService();
        ReflectionTestUtils.setField(embeddingService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(embeddingService, "embeddingApiUrl", SINGLE_URL);
        ReflectionTestUtils.setField(embeddingService, "embeddingModel", "bge-m3");
        ReflectionTestUtils.setField(embeddingService, "embeddingBatchSize", 16);
    }

    /** 批量接口可用时，一次请求拿到全部向量且顺序与输入一致 */
    @Test
    void batchShouldCallEmbedEndpointAndKeepOrder() {
        when(restTemplate.postForEntity(eq(BATCH_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"embeddings\":[[0.1,0.2],[0.3,0.4]]}"));

        List<float[]> vectors = embeddingService.embedBatch(List.of("第一段", "第二段"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{0.1f, 0.2f}, vectors.get(0), 0.0001f);
        assertArrayEquals(new float[]{0.3f, 0.4f}, vectors.get(1), 0.0001f);
        verify(restTemplate, times(1)).postForEntity(eq(BATCH_URL), any(HttpEntity.class), eq(String.class));
    }

    /** OpenAI 兼容形态：data 数组乱序时按 index 归位 */
    @Test
    void batchShouldRestoreOrderFromOpenAiShape() {
        when(restTemplate.postForEntity(eq(BATCH_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"data\":[{\"index\":1,\"embedding\":[0.3]},{\"index\":0,\"embedding\":[0.1]}]}"));

        List<float[]> vectors = embeddingService.embedBatch(List.of("甲", "乙"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{0.1f}, vectors.get(0), 0.0001f);
        assertArrayEquals(new float[]{0.3f}, vectors.get(1), 0.0001f);
    }

    /** 批量接口不支持（4xx）时回退逐条，且后续批次不再尝试批量 */
    @Test
    void batchShouldFallBackToSingleCallOnceWhenUnsupported() {
        when(restTemplate.postForEntity(eq(BATCH_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(restTemplate.postForEntity(eq(SINGLE_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"embedding\":[0.5,0.6]}"));

        List<float[]> first = embeddingService.embedBatch(List.of("一段", "二段"));
        List<float[]> second = embeddingService.embedBatch(List.of("三段"));

        assertEquals(2, first.size());
        assertEquals(1, second.size());
        assertArrayEquals(new float[]{0.5f, 0.6f}, first.get(0), 0.0001f);
        // 批量端点只试探一次：第二批直接走逐条
        verify(restTemplate, times(1)).postForEntity(eq(BATCH_URL), any(HttpEntity.class), eq(String.class));
        verify(restTemplate, times(3)).postForEntity(eq(SINGLE_URL), any(HttpEntity.class), eq(String.class));
    }

    /** 上游少返回向量时必须报错，不能返回错位的向量 */
    @Test
    void batchShouldRejectMismatchedCount() {
        when(restTemplate.postForEntity(eq(BATCH_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"embeddings\":[[0.1]]}"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> embeddingService.embedBatch(List.of("甲", "乙")));

        assertEquals("Failed to generate embedding", error.getMessage());
    }
}
