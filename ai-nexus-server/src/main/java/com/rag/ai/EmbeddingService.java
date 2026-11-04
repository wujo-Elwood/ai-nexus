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
     * 内部逐条调用 {@link #embed(String)} 方法，适用于知识库上传文件后对每个文本块批量生成向量。
     *
     * @param texts 待向量化的文本列表（通常是文件拆分后的多个文本块）
     * @return 与输入文本顺序对应的向量列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(embed(text));
        }
        return embeddings;
    }
}
