package com.rag.health;

import com.rag.entity.ModelProvider;
import com.rag.mapper.ModelProviderMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 系统健康检查服务
 * 聚合数据库、向量库、模型服务、磁盘和后台线程池状态
 */
@Slf4j
@Service
public class SystemHealthService {

    private final DataSource dataSource;
    private final ModelProviderMapper modelProviderMapper;
    private RestTemplate restTemplate = new RestTemplate();

    /** Spring 容器中按 Bean 名称注入的后台线程池 */
    @Autowired(required = false)
    private Map<String, Executor> threadPools = Map.of();

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${qdrant.base-url:http://${qdrant.host}:${qdrant.port}}")
    private String qdrantBaseUrl;

    @Value("${qdrant.collection}")
    private String qdrantCollection;

    @Value("${embedding.api-url}")
    private String embeddingApiUrl;

    @Value("${embedding.health-url:}")
    private String embeddingHealthUrl;

    @Value("${health.timeout-ms:3000}")
    private int timeoutMs = 3000;

    @Value("${health.disk-warning-percent:85}")
    private int diskWarningPercent = 85;

    /**
     * 创建系统健康检查服务
     */
    public SystemHealthService(DataSource dataSource, ModelProviderMapper modelProviderMapper) {
        this.dataSource = dataSource;
        this.modelProviderMapper = modelProviderMapper;
    }

    /**
     * 使用配置的连接和读取超时初始化健康检查客户端
     */
    @PostConstruct
    void initializeRestTemplate() {
        long safeTimeoutMs = Math.max(100, timeoutMs);
        restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(safeTimeoutMs))
                .setReadTimeout(Duration.ofMillis(safeTimeoutMs))
                .build();
    }

    /**
     * 执行全部健康检查并生成面板响应
     */
    public SystemHealthResponse checkOverview() {
        SystemHealthResponse response = new SystemHealthResponse();
        response.setCheckedAt(LocalDateTime.now());
        response.getComponents().put("mysql", checkMysql());
        response.getComponents().put("qdrant", checkHttpComponent("qdrant", "Qdrant", qdrantBaseUrl + "/collections/" + qdrantCollection));
        response.getComponents().put("embedding", checkHttpComponent("embedding", "Embedding", resolveEmbeddingHealthUrl()));
        response.getComponents().put("llm", checkLlm());
        response.getComponents().put("disk", checkDisk());
        for (Map.Entry<String, Executor> entry : threadPools.entrySet()) {
            // 只展示文件处理、聊天流式和生图三个业务线程池，排除 Spring 内部调度线程池
            if ("fileProcessExecutor".equals(entry.getKey())
                    || "chatStreamExecutor".equals(entry.getKey())
                    || "imageGenerateExecutor".equals(entry.getKey())) {
                response.getThreadPools().put(entry.getKey(), inspectThreadPool(entry.getKey(), entry.getValue()));
            }
        }
        response.setStatus(resolveOverallStatus(response));
        return response;
    }

    /**
     * 检查 MySQL 是否可以执行最小查询
     */
    private HealthComponent checkMysql() {
        long startedAt = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return component("mysql", "MySQL", HealthStatus.DOWN, startedAt, "数据库未返回检查结果");
            }
            return component("mysql", "MySQL", HealthStatus.UP, startedAt, "连接正常");
        } catch (Exception e) {
            log.warn("MySQL health check failed", e);
            return component("mysql", "MySQL", HealthStatus.DOWN, startedAt, "数据库连接失败");
        }
    }

    /**
     * 检查一个不需要提交业务数据的 HTTP 依赖
     */
    private HealthComponent checkHttpComponent(String key, String label, String url) {
        long startedAt = System.nanoTime();
        if (url == null || url.isBlank()) {
            return component(key, label, HealthStatus.NOT_CONFIGURED, startedAt, "未配置健康检查地址");
        }
        try {
            restTemplate.getForEntity(url, String.class);
            return component(key, label, HealthStatus.UP, startedAt, "服务可访问");
        } catch (Exception e) {
            log.warn("{} health check failed", label, e);
            return component(key, label, HealthStatus.DOWN, startedAt, "服务不可访问");
        }
    }

    /**
     * 检查当前激活的 LLM 供应商，不发送消耗 Token 的聊天请求
     */
    private HealthComponent checkLlm() {
        long startedAt = System.nanoTime();
        ModelProvider provider;
        try {
            provider = modelProviderMapper.findActive();
        } catch (Exception e) {
            return component("llm", "LLM", HealthStatus.DOWN, startedAt, "模型配置读取失败");
        }
        if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            return component("llm", "LLM", HealthStatus.NOT_CONFIGURED, startedAt, "未配置激活模型");
        }
        String url = normalizeBaseUrl(provider.getBaseUrl()) + "/models";
        try {
            if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
                restTemplate.getForEntity(url, String.class);
            } else {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(provider.getApiKey());
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            }
            HealthComponent result = component("llm", "LLM", HealthStatus.UP, startedAt, "激活模型服务可访问");
            result.getDetails().put("provider", provider.getName());
            result.getDetails().put("model", provider.getModel());
            return result;
        } catch (Exception e) {
            log.warn("LLM health check failed", e);
            return component("llm", "LLM", HealthStatus.DOWN, startedAt, "激活模型服务不可访问");
        }
    }

    /**
     * 检查上传目录容量和使用率
     */
    private HealthComponent checkDisk() {
        long startedAt = System.nanoTime();
        try {
            Path path = Paths.get(uploadDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                return component("disk", "磁盘空间", HealthStatus.DOWN, startedAt, "上传目录不可用");
            }
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            long used = Math.max(0L, total - usable);
            double usagePercent = total <= 0 ? 0 : used * 100.0 / total;
            HealthStatus status = usagePercent >= diskWarningPercent ? HealthStatus.DEGRADED : HealthStatus.UP;
            HealthComponent result = component("disk", "磁盘空间", status, startedAt,
                    status == HealthStatus.UP ? "磁盘空间正常" : "磁盘空间使用率较高");
            result.getDetails().put("totalBytes", total);
            result.getDetails().put("usedBytes", used);
            result.getDetails().put("freeBytes", usable);
            result.getDetails().put("usagePercent", Math.round(usagePercent * 100.0) / 100.0);
            return result;
        } catch (IOException | RuntimeException e) {
            log.warn("Disk health check failed", e);
            return component("disk", "磁盘空间", HealthStatus.DOWN, startedAt, "磁盘空间检查失败");
        }
    }

    /**
     * 读取可观测线程池指标
     */
    private ThreadPoolHealth inspectThreadPool(String name, Executor executor) {
        ThreadPoolHealth result = new ThreadPoolHealth();
        result.setName(name);
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            result.setStatus(HealthStatus.NOT_CONFIGURED);
            return result;
        }
        result.setStatus(HealthStatus.UP);
        result.setCorePoolSize(pool.getCorePoolSize());
        result.setMaximumPoolSize(pool.getMaximumPoolSize());
        result.setPoolSize(pool.getPoolSize());
        result.setActiveCount(pool.getActiveCount());
        result.setQueueSize(pool.getQueue().size());
        result.setCompletedTaskCount(pool.getCompletedTaskCount());
        return result;
    }

    /**
     * 计算系统总体状态
     */
    private HealthStatus resolveOverallStatus(SystemHealthResponse response) {
        boolean hasDown = response.getComponents().values().stream().anyMatch(item -> item.getStatus() == HealthStatus.DOWN)
                || response.getThreadPools().values().stream().anyMatch(item -> item.getStatus() == HealthStatus.DOWN);
        if (hasDown) {
            return HealthStatus.DOWN;
        }
        boolean hasDegradedOrUnconfigured = response.getComponents().values().stream()
                .anyMatch(item -> item.getStatus() == HealthStatus.DEGRADED || item.getStatus() == HealthStatus.NOT_CONFIGURED)
                || response.getThreadPools().values().stream()
                .anyMatch(item -> item.getStatus() == HealthStatus.DEGRADED || item.getStatus() == HealthStatus.NOT_CONFIGURED);
        return hasDegradedOrUnconfigured ? HealthStatus.DEGRADED : HealthStatus.UP;
    }

    /**
     * 创建统一组件结果并计算耗时
     */
    private HealthComponent component(String key, String label, HealthStatus status, long startedAt, String message) {
        HealthComponent result = new HealthComponent();
        result.setKey(key);
        result.setLabel(label);
        result.setStatus(status);
        result.setLatencyMs((System.nanoTime() - startedAt) / 1_000_000);
        result.setMessage(message);
        result.setCheckedAt(LocalDateTime.now());
        return result;
    }

    /**
     * 推导 Ollama 默认轻量健康地址
     */
    private String resolveEmbeddingHealthUrl() {
        if (embeddingHealthUrl != null && !embeddingHealthUrl.isBlank()) {
            return embeddingHealthUrl;
        }
        if (embeddingApiUrl == null || embeddingApiUrl.isBlank()) {
            return "";
        }
        if (embeddingApiUrl.endsWith("/api/embeddings")) {
            return embeddingApiUrl.substring(0, embeddingApiUrl.length() - "/api/embeddings".length()) + "/api/tags";
        }
        return embeddingApiUrl;
    }

    /**
     * 规范化 OpenAI 兼容服务基础地址
     */
    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        return normalized;
    }
}
