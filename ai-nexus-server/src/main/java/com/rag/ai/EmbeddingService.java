package com.rag.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量嵌入服务
 * 调用外部 Embedding API（如 Ollama）将文本转换为向量表示（float 数组）。
 * 生成的向量用于 RAG 检索：用户提问时将问题文本向量化，再与知识库中的文档向量做相似度匹配。
 */
@Slf4j
@Service
public class EmbeddingService {

    /** Embedding API 的地址，从 application.yml 中读取，例如 http://localhost:11434/api/embeddings */
    @Value("${embedding.api-url}")
    private String embeddingApiUrl;

    /** 使用的嵌入模型名称，从 application.yml 中读取，例如 nomic-embed-text */
    @Value("${embedding.model}")
    private String embeddingModel;

    /** RestTemplate 设置连接与读取超时：外部 Embedding 服务挂起时不至于无限占用调用线程 */
    private final RestTemplate restTemplate = buildTimeoutRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 批量向量化时单次请求携带的文本条数：Ollama 一次推理多条的吞吐远高于逐条往返 */
    @Value("${embedding.batch-size:16}")
    private int embeddingBatchSize;

    /** 批量接口可用性缓存：null=还没试过，true=可用，false=已知不支持（避免每一批都白试一次） */
    private volatile Boolean batchSupported = null;

    /**
     * 构建带超时的 RestTemplate
     * 连接 5 秒、读取 120 秒：单条向量化很快，但知识库入库会批量调用，CPU 推理慢时单次也可能偏慢
     */
    private RestTemplate buildTimeoutRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(120_000);
        return new RestTemplate(factory);
    }

    /**
     * 将单条文本转换为向量（float 数组）
     *
     * 整体流程：
     * 1. 构造请求体：包含模型名称和待转换的文本
     * 2. 以 POST 方式调用 Embedding API
     * 3. 解析返回的 JSON，提取 "embedding" 字段（一个浮点数数组）
     * 4. 将 JSON 数组转为 Java 的 float[] 返回
     *
     * API 请求示例：
     *   POST {embeddingApiUrl}
     *   Body: { "model": "nomic-embed-text", "prompt": "这是一段文本" }
     *
     * API 响应示例：
     *   { "embedding": [0.0123, -0.0456, 0.0789, ...] }
     *
     * @param text 待向量化的文本内容
     * @return 向量数组，维度取决于所使用的模型（如 nomic-embed-text 为 768 维）
     * @throws RuntimeException 当 API 调用失败或响应中没有 embedding 数据时抛出
     */
    public float[] embed(String text) {
        try {
            // 1. 构造请求体：模型名 + 待嵌入的文本
            Map<String, Object> request = Map.of("model", embeddingModel, "prompt", text);

            // 2. 设置请求头为 JSON 格式
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 3. 将请求体序列化为 JSON 字符串，并包装成 HttpEntity
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

            // 4. 发送 POST 请求到 Embedding API，获取原始 JSON 响应
            ResponseEntity<String> response = restTemplate.postForEntity(embeddingApiUrl, entity, String.class);

            // 5. 将 JSON 字符串解析为 JsonNode 树结构，方便逐字段读取
            JsonNode result = objectMapper.readTree(response.getBody());

            // 6. 从响应中提取 "embedding" 字段，它是一个浮点数数组
            JsonNode embedding = result.get("embedding");
            if (embedding != null && embedding.isArray()) {
                // 7. 遍历 JSON 数组，将每个 double 值转为 float，存入结果数组
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                return vector;
            }
            // 响应中没有 embedding 字段，说明 API 返回异常
            throw new RuntimeException("No embedding data in response");
        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", e.getMessage());
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    /**
     * 批量将多条文本转换为向量
     *
     * 优先用批量接口（Ollama `/api/embed` 或 OpenAI 兼容 `/v1/embeddings`，两者都接受 input 数组），
     * 一次请求处理一批文本；接口不被支持（返回 4xx）时自动降级为逐条调用，且只降级一次，
     * 后续批次不再重复试探。向量库写入是批量 100 条一批，这里按 embedding.batch-size 切分，
     * 让「向量化」与「写 Qdrant」都保持小批次，避免整份大文件堆在内存里。
     *
     * @param texts 待向量化的文本列表（通常是文件拆分后的多个文本块）
     * @return 与输入文本顺序一一对应的向量列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return embeddings;
        }
        int size = Math.max(1, embeddingBatchSize);
        for (int start = 0; start < texts.size(); start += size) {
            int end = Math.min(start + size, texts.size());
            embeddings.addAll(embedSlice(texts.subList(start, end)));
        }
        return embeddings;
    }

    /**
     * 向量化一批文本：先试批量接口，确认不支持后走逐条
     */
    private List<float[]> embedSlice(List<String> texts) {
        if (Boolean.FALSE.equals(batchSupported)) {
            return embedSequentially(texts);
        }
        try {
            List<float[]> vectors = postBatch(texts);
            batchSupported = Boolean.TRUE;
            return vectors;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 4xx 说明该地址没有批量语义（例如只支持单条 prompt 的老接口），降级并记住
            log.warn("Embedding 批量接口不可用（HTTP {}），已回退逐条向量化: {}",
                    e.getStatusCode().value(), e.getStatusText());
            batchSupported = Boolean.FALSE;
            return embedSequentially(texts);
        } catch (Exception e) {
            // 网络抖动、超时等偶发问题不应改变"是否支持批量"的判断，直接抛出由上层重试；
            // 文案与单条路径保持一致，避免前端/日志里突然出现一种新的错误措辞
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    /**
     * 逐条向量化（原有行为，作为批量接口的后备实现）
     */
    private List<float[]> embedSequentially(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /**
     * 调用批量接口并解析结果
     */
    private List<float[]> postBatch(List<String> texts) throws Exception {
        Map<String, Object> request = Map.of("model", embeddingModel, "input", texts);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(batchUrl(), entity, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        return parseBatchResponse(root, texts.size());
    }

    /**
     * 批量接口地址：Ollama 的单条接口是 /api/embeddings，批量接口是 /api/embed；
     * OpenAI 兼容的 /v1/embeddings 本身就支持数组，原样使用
     */
    private String batchUrl() {
        String url = embeddingApiUrl == null ? "" : embeddingApiUrl.trim();
        if (url.endsWith("/api/embeddings")) {
            return url.substring(0, url.length() - "/api/embeddings".length()) + "/api/embed";
        }
        return url;
    }

    /**
     * 解析批量响应：兼容 Ollama 的 embeddings 数组与 OpenAI 的 data[].embedding
     */
    private List<float[]> parseBatchResponse(JsonNode root, int expected) {
        List<float[]> vectors = new ArrayList<>(expected);
        JsonNode ollamaShape = root == null ? null : root.get("embeddings");
        if (ollamaShape != null && ollamaShape.isArray()) {
            for (JsonNode node : ollamaShape) {
                vectors.add(toVector(node));
            }
            return validated(vectors, expected);
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("批量向量响应缺少 embeddings 或 data 字段");
        }
        List<JsonNode> ordered = new ArrayList<>();
        for (JsonNode node : data) {
            ordered.add(node);
        }
        // OpenAI 风格结果可能乱序，按 index 归位；没有 index 字段时 stable sort 保持原顺序
        ordered.sort(java.util.Comparator.comparingInt(node -> node.path("index").asInt(0)));
        for (JsonNode node : ordered) {
            vectors.add(toVector(node.get("embedding")));
        }
        return validated(vectors, expected);
    }

    /**
     * 校验批量结果条数，防止上游少返回时把向量错配到别的文本块上
     */
    private List<float[]> validated(List<float[]> vectors, int expected) {
        if (vectors.size() != expected) {
            throw new IllegalStateException("批量向量数量不匹配：期望 " + expected + " 条，实际 " + vectors.size() + " 条");
        }
        return vectors;
    }

    /**
     * JSON 数组转 float[]
     */
    private float[] toVector(JsonNode embedding) {
        if (embedding == null || !embedding.isArray()) {
            throw new IllegalStateException("批量向量响应中的向量格式不正确");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }
}
