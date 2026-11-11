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
 * 查询改写器
 * 用 LLM 把用户的口语化问题改写成适合向量检索的独立查询
 */
@Slf4j
@Component
public class QueryRewriter {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单轮改写：去掉口语化成分，只保留可检索的关键信息 */
    private static final String REWRITE_PROMPT =
            "You are a query rewriting assistant. Rewrite the user colloquial question into an independent, complete query suitable for vector search. "
            + "Rules: remove pronouns, add missing context, output only the rewritten query without explanation.\n\nUser question: ";

    /**
     * 多轮改写：结合对话历史，把「它的上限是多少」这类依赖上下文的追问补全成独立问题
     */
    private static final String MULTI_TURN_REWRITE_PROMPT =
            "You are a query rewriting assistant for a retrieval system. "
            + "Given the conversation history and the user's latest question, rewrite the latest question into an independent, self-contained query suitable for vector search. "
            + "Rules: replace pronouns and omitted subjects with the concrete terms they refer to in the history, keep the original wording when possible, "
            + "do not answer the question, output only the rewritten query without explanation.";

    public QueryRewriter(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    /**
     * 改写用户查询，使其更适合向量检索
     * 如果改写失败，返回原始查询
     */
    public String rewrite(String query) {
        return callModel(REWRITE_PROMPT, query, query);
    }

    /**
     * 结合对话历史改写用户查询，用于多轮追问
     *
     * @param question    用户本轮原始问题
     * @param historyText 已经格式化好的历史对话（形如「用户：…\n助手：…」），为空时退化为单轮改写
     * @return 补全后的独立查询；失败时返回原始问题
     */
    public String rewriteWithHistory(String question, String historyText) {
        if (question == null || question.isBlank()) {
            return question;
        }
        if (historyText == null || historyText.isBlank()) {
            return rewrite(question);
        }
        // 第1步：把历史对话与本轮问题拼成一次模型请求的内容
        String content = "Conversation history:\n" + historyText
                + "\n\nLatest user question: " + question;
        // 第2步：调用模型，失败时回落到原始问题
        return callModel(MULTI_TURN_REWRITE_PROMPT, content, question);
    }

    /**
     * 统一的模型调用：任何异常都返回 fallback，保证检索链路不因改写失败而中断
     */
    private String callModel(String prompt, String content, String fallback) {
        try {
            ModelProvider provider = modelProviderService.getActive();
            String requestBody = buildRequest(prompt, content, provider);
            //构建 LLM API 请求地址
            String endpoint = buildEndpoint(provider);
            HttpURLConnection conn = openConnection(endpoint, provider);
            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

            if (conn.getResponseCode() >= 400) {
                log.warn("Query rewrite failed, using original query");
                conn.disconnect();
                return fallback;
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) { response.append(line); }
            }
            conn.disconnect();
            JsonNode result = objectMapper.readTree(response.toString());
            String rewritten = result.get("choices").get(0).get("message").get("content").asText().trim();
            // 去掉模型可能加上的引号或换行
            rewritten = rewritten.replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
            if (rewritten.isEmpty()) {
                return fallback;
            }
            if (rewritten.length() > 200) {
                rewritten = rewritten.substring(0, 200);
            }
            log.debug("Query rewritten: '{}' -> '{}'", content, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("Query rewrite error: {}, using original", e.getMessage());
            return fallback;
        }
    }


    /**
     * 组装模型请求体
     * 查询重写（Query Rewriting）方法，核心作用是：调用大语言模型（LLM）将用户输入的模糊、
     * 口语化或不够精确的查询，改写成更清晰、更适合检索的表述，从而提升搜索或RAG系统的召回效果
     */
    private String buildRequest(String prompt, String content, ModelProvider provider) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", provider.getModel());
        body.put("temperature", 0.1);
        body.put("max_tokens", 120);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt + content);
        return objectMapper.writeValueAsString(body);
    }

    //构建 LLM API 请求地址
    private String buildEndpoint(ModelProvider provider) {
        String baseUrl = provider.getBaseUrl().replaceAll("/+$", "");
        if (baseUrl.endsWith("/chat/completions")) return baseUrl;
        if (baseUrl.endsWith("/v1")) return baseUrl + "/chat/completions";
        return baseUrl + "/v1/chat/completions";
    }

    private HttpURLConnection openConnection(String endpoint, ModelProvider provider) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.getApiKey());
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        return conn;
    }
}
