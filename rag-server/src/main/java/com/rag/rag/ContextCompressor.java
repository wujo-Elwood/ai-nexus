package com.rag.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 上下文压缩器
 *
 * 作用：当检索到的文本块较多或较长时，调用 LLM 对上下文进行压缩，
 * 只保留与用户问题相关的信息，去除无关内容。
 *
 * 解决的问题：
 * 1. topK=5 的 chunk 全部塞进 prompt，可能超过模型上下文窗口
 * 2. 检索到的 chunk 中可能包含与问题无关的段落，引入噪声
 * 3. 压缩后的上下文更精炼，模型回答质量更高
 *
 * 压缩策略：
 * - 将用户问题和检索到的文本块一起发给 LLM
 * - 要求 LLM 只提取与问题相关的事实和信息
 * - 返回压缩后的文本，长度通常为原文的 30%-50%
 *
 * 降级策略：
 * - 如果压缩失败（LLM 调用超时、异常等），直接返回原始上下文
 * - 如果原始上下文较短（不超过 2000 字），跳过压缩直接返回
 */
@Slf4j
@Component
public class ContextCompressor {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 压缩提示词模板
     * 要求 LLM 从检索结果中只提取与问题相关的信息，去除无关内容
     */
    private static final String COMPRESS_PROMPT =
            "你是一个信息提取助手。请从以下知识库内容中，只提取与用户问题相关的关键信息。\n"
            + "要求：\n"
            + "1. 只保留与问题直接相关的内容\n"
            + "2. 去除无关信息和重复内容\n"
            + "3. 保留原文中的关键数据、步骤、规则等\n"
            + "4. 保持原文表述，不要改写\n"
            + "5. 如果内容中包含表格数据，请保留表格结构\n\n"
            + "6. 必须原样保留每段内容前的 [来源: 文件名, 第N段] 标记\n\n"
            + "用户问题：{question}\n\n"
            + "知识库内容：\n{context}\n\n"
            + "请输出精炼后的相关内容：";

    /**
     * 上下文长度阈值（字符数）
     * 低于此长度的上下文不需要压缩，直接返回
     */
    private static final int COMPRESS_THRESHOLD = 2000;

    /**
     * 构造注入
     *
     * @param modelProviderService 模型供应商服务，用于获取当前激活的 LLM 配置
     */
    public ContextCompressor(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    /**
     * 压缩上下文
     * 将检索到的文本块和用户问题一起发给 LLM，提取与问题相关的关键信息
     *
     * @param question 用户的原始问题
     * @param context  检索到的上下文文本（多个 chunk 拼接）
     * @return 压缩后的上下文文本。压缩失败时返回原始上下文。
     */
    public String compress(String question, String context) {
        // 上下文较短时不需要压缩，直接返回
        if (context == null || context.isEmpty() || context.length() < COMPRESS_THRESHOLD) {
            return context;
        }

        try {
            // 第1步：获取当前激活的模型供应商配置
            ModelProvider provider = modelProviderService.getActive();

            // 第2步：构建压缩请求体
            String prompt = COMPRESS_PROMPT
                    .replace("{question}", question)
                    .replace("{context}", context);
            String requestBody = buildRequest(prompt, provider);

            // 第3步：调用 LLM 进行压缩
            String endpoint = buildEndpoint(provider);
            HttpURLConnection conn = openConnection(endpoint, provider);
            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

            // 第4步：检查响应状态
            if (conn.getResponseCode() >= 400) {
                log.warn("Context compression failed, using original context");
                conn.disconnect();
                return context;
            }

            // 第5步：读取压缩结果
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();

            // 第6步：解析 LLM 返回的压缩文本
            JsonNode result = objectMapper.readTree(response.toString());
            String compressed = result.get("choices").get(0).get("message").get("content").asText().trim();

            // 第7步：校验压缩结果（不能为空，不能比原文更长）
            if (compressed.isEmpty() || compressed.length() >= context.length()) {
                log.debug("Compression result not better, using original");
                return context;
            }
            //原文包含来源标记时，压缩结果丢失来源就退回原文，保证引用可以校验
            if (context.contains("[来源:") && !compressed.contains("[来源:")) {
                log.warn("Compression removed source markers, using original context");
                return context;
            }

            log.debug("Context compressed: {} -> {} chars ({}% reduction)",
                    context.length(), compressed.length(),
                    (100 - compressed.length() * 100 / context.length()));
            return compressed;

        } catch (Exception e) {
            // 压缩失败时静默降级，返回原始上下文
            log.warn("Context compression error: {}, using original", e.getMessage());
            return context;
        }
    }

    /**
     * 构建 LLM 请求体
     * 使用低温度（0.1）确保压缩结果稳定，不引入创造性内容
     *
     * @param prompt   完整的压缩提示词（含用户问题和上下文）
     * @param provider 模型供应商配置
     * @return JSON 请求体字符串
     */
    private String buildRequest(String prompt, ModelProvider provider) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", provider.getModel());
        body.put("temperature", 0.1);  // 低温度 = 更稳定的提取结果
        body.put("max_tokens", 2000);  // 限制输出长度
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 构建 LLM API 端点地址
     * 兼容不同供应商的 URL 格式
     *
     * @param provider 模型供应商配置
     * @return 完整的 API 端点 URL
     */
    private String buildEndpoint(ModelProvider provider) {
        String baseUrl = provider.getBaseUrl().replaceAll("/+$", "");
        if (baseUrl.endsWith("/chat/completions")) return baseUrl;
        if (baseUrl.endsWith("/v1")) return baseUrl + "/chat/completions";
        return baseUrl + "/v1/chat/completions";
    }

    /**
     * 打开到 LLM API 的 HTTP 连接
     *
     * @param endpoint API 地址
     * @param provider 供应商配置
     * @return HTTP 连接对象
     */
    private HttpURLConnection openConnection(String endpoint, ModelProvider provider) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.getApiKey());
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);  // 连接超时 5 秒
        conn.setReadTimeout(30000);    // 读取超时 30 秒（压缩可能需要较长时间）
        return conn;
    }
}
