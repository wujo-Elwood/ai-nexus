package com.rag.health;

import com.rag.entity.ModelProvider;
import com.rag.mapper.ModelProviderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 系统健康服务测试
 * 验证基础设施状态聚合和敏感信息脱敏行为
 */
class SystemHealthServiceTest {

    @TempDir
    Path tempDir;

    /**
     * 测试 MySQL、磁盘和线程池状态能够返回统一健康结构
     */
    @Test
    void healthResponseShouldReportMysqlDiskAndThreadPool() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        ModelProviderMapper modelProviderMapper = mock(ModelProviderMapper.class);
        ModelProvider provider = new ModelProvider();
        provider.setName("test-llm");
        provider.setBaseUrl("http://llm.invalid/v1");
        provider.setModel("test-model");
        when(modelProviderMapper.findActive()).thenReturn(provider);

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        SystemHealthService service = new SystemHealthService(dataSource, modelProviderMapper);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "threadPools", Map.of("fileProcessExecutor", executor));
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "qdrantBaseUrl", "http://qdrant.invalid");
        ReflectionTestUtils.setField(service, "qdrantCollection", "rag_chunks");
        ReflectionTestUtils.setField(service, "embeddingHealthUrl", "http://embedding.invalid/health");

        SystemHealthResponse response = service.checkOverview();

        assertNotNull(response.getCheckedAt());
        assertTrue(response.getComponents().get("mysql").isUp());
        assertTrue(response.getComponents().get("disk").isUp());
        assertNotNull(response.getThreadPools().get("fileProcessExecutor"));
        executor.shutdownNow();
    }

    /**
     * 测试外部依赖异常时不向前端泄露密钥或绝对路径
     */
    @Test
    void externalFailureShouldBeSanitized() {
        DataSource dataSource = mock(DataSource.class);
        ModelProviderMapper modelProviderMapper = mock(ModelProviderMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenThrow(new RuntimeException("secret=abc123 path=D:/private/data"));
        SystemHealthService service = new SystemHealthService(dataSource, modelProviderMapper);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "threadPools", Map.of());
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "qdrantBaseUrl", "http://qdrant.invalid");
        ReflectionTestUtils.setField(service, "qdrantCollection", "rag_chunks");
        ReflectionTestUtils.setField(service, "embeddingHealthUrl", "http://embedding.invalid/health");

        SystemHealthResponse response = service.checkOverview();
        String message = response.getComponents().get("qdrant").getMessage();

        assertFalse(message.contains("abc123"));
        assertFalse(message.contains("D:/private/data"));
    }
}
