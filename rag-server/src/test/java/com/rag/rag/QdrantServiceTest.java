package com.rag.rag;

import com.rag.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Qdrant 服务测试
 * 验证集合维度解析和向量维度不匹配时的明确报错
 */
class QdrantServiceTest {

    /**
     * 创建带测试地址的 Qdrant 服务
     */
    private QdrantService buildService() {
        QdrantService service = new QdrantService();
        ReflectionTestUtils.setField(service, "host", "qdrant.test");
        ReflectionTestUtils.setField(service, "port", 6333);
        ReflectionTestUtils.setField(service, "collection", "rag_chunks");
        return service;
    }

    /**
     * 为 Qdrant 服务内部 HTTP 客户端创建模拟服务器
     */
    private MockRestServiceServer bindServer(QdrantService service) {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    /**
     * 测试从集合信息中解析真实向量维度
     */
    @Test
    void parseVectorSizeShouldReadDimensionFromCollectionInfo() {
        QdrantService service = new QdrantService();
        String collectionInfo = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}}},\"status\":\"ok\"}";

        assertEquals(1024, service.parseVectorSize(collectionInfo));
    }

    /**
     * 测试命名向量等未知结构时返回 0，跳过维度校验
     */
    @Test
    void parseVectorSizeShouldReturnZeroForUnknownStructure() {
        QdrantService service = new QdrantService();
        String namedVectors = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"text\":{}}}}}}";

        assertEquals(0, service.parseVectorSize(namedVectors));
        assertEquals(0, service.parseVectorSize("not-json"));
    }

    /**
     * 测试维度不匹配时抛出可行动的业务异常，匹配时正常通过
     */
    @Test
    void ensureCollectionShouldRejectMismatchedDimension() {
        QdrantService service = new QdrantService();
        ReflectionTestUtils.setField(service, "collectionReady", true);
        ReflectionTestUtils.setField(service, "collectionVectorSize", 1024);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ensureCollection(768));
        assertTrue(exception.getMessage().contains("向量维度不匹配"));
        assertTrue(exception.getMessage().contains("1024"));
        assertTrue(exception.getMessage().contains("768"));

        //维度一致时不抛异常
        assertDoesNotThrow(() -> service.ensureCollection(1024));
    }

    /**
     * 测试集合查询服务异常时不能误判为不存在并尝试创建
     */
    @Test
    void ensureCollectionShouldExposeLookupFailure() {
        QdrantService service = buildService();
        MockRestServiceServer server = bindServer(service);
        //模拟 Qdrant 服务不可用
        server.expect(requestTo("http://qdrant.test:6333/collections/rag_chunks"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ensureCollection(1024));

        assertEquals(503, exception.getCode());
        //确认没有继续发送创建集合请求
        server.verify();
    }

    /**
     * 测试集合存在但维度结构无法识别时拒绝继续使用
     */
    @Test
    void ensureCollectionShouldRejectUnknownStoredDimension() {
        QdrantService service = buildService();
        MockRestServiceServer server = bindServer(service);
        //返回不包含 vectors.size 的集合信息
        server.expect(requestTo("http://qdrant.test:6333/collections/rag_chunks"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":{\"config\":{\"params\":{\"vectors\":{}}}}}",
                        MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ensureCollection(1024));

        assertTrue(exception.getMessage().contains("无法读取"));
        server.verify();
    }

    /**
     * 测试集合不存在时按知识库删除向量视为幂等成功
     */
    @Test
    void deleteByKbIdShouldIgnoreMissingCollection() {
        QdrantService service = buildService();
        MockRestServiceServer server = bindServer(service);
        //模拟首次使用前 collection 尚未创建
        server.expect(requestTo("http://qdrant.test:6333/collections/rag_chunks/points/delete"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertDoesNotThrow(() -> service.deleteByKbId(3L));
        server.verify();
    }

    /**
     * 测试全局重建期间搜索返回明确冲突错误
     */
    @Test
    void searchShouldRejectRequestsDuringGlobalRebuild() {
        QdrantService service = buildService();
        //标记开始全局重建
        service.startRebuild();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.searchWithScore(new float[]{0.1f, 0.2f}, 5, 3L));

        assertEquals(409, exception.getCode());
        assertTrue(exception.getMessage().contains("重建"));
    }

    /**
     * 测试全局重建期间普通文件向量写入会被拒绝
     */
    @Test
    void upsertShouldRejectRequestsDuringGlobalRebuild() {
        QdrantService service = buildService();
        service.startRebuild();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upsertBatch(List.of(new QdrantService.VectorPoint(1L,
                        new float[]{0.1f, 0.2f}, Map.of("kb_id", 3L)))));

        assertEquals(409, exception.getCode());
        assertTrue(exception.getMessage().contains("写入"));
    }

    /**
     * 测试全局重建会删除旧集合并按新维度重新创建
     */
    @Test
    void recreateCollectionShouldReplaceStoredDimension() {
        QdrantService service = buildService();
        MockRestServiceServer server = bindServer(service);
        //先删除旧 collection，再创建新维度 collection
        server.expect(requestTo("http://qdrant.test:6333/collections/rag_chunks"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());
        server.expect(requestTo("http://qdrant.test:6333/collections/rag_chunks"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        service.startRebuild();
        service.recreateCollection(768);
        service.finishRebuild();

        assertDoesNotThrow(() -> service.ensureCollection(768));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ensureCollection(1024));
        assertTrue(exception.getMessage().contains("768"));
        server.verify();
    }
}
