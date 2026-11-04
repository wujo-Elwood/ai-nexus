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

    private static final String REWRITE_PROMPT =
            "You are a query rewriting assistant. Rewrite the user colloquial question into an independent, complete query suitable for vector search. "
            + "Rules: remove pronouns, add missing context, output only the rewritten query without explanation.\n\nUser question: ";

    public QueryRewriter(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    /**
     * 改写用户查询，使其更适合向量检索
     * 如果改写失败，返回原始查询
     */
    public String rewrite(String query) {
        try {
            ModelProvider provider = modelProviderService.getActive();
            /**
             * 查询重写（Query Rewriting）方法，核心作用是：
             * 调用大语言模型（LLM）将用户输入的模糊、口语化或不够精确的查询，改写成更清晰、
             * 更适合检索的表述，从而提升搜索或RAG系统的召回效果
             */
            String requestBody = buildRequest(query, provider);
            //构建 LLM API 请求地址
            String endpoint = buildEndpoint(provider);
            HttpURLConnection conn = openConnection(endpoint, provider);
            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

            if (conn.getResponseCode() >= 400) {
                log.warn("Query rewrite failed, using original query");
                conn.disconnect();
                return query;
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
            if (rewritten.length() > 200) {
                rewritten = rewritten.substring(0, 200);
            }
            log.debug("Query rewritten: '{}' -> '{}'", query, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("Query rewrite error: {}, using original", e.getMessage());
            return query;
        }
    }


    /**
     * 查询重写（Query Rewriting）方法，核心作用是：调用大语言模型（LLM）将用户输入的模糊、
     * 口语化或不够精确的查询，改写成更清晰、更适合检索的表述，从而提升搜索或RAG系统的召回效果
     * @param query
     * @param provider
     * @return
     * @throws Exception
     */
    private String buildRequest(String query, ModelProvider provider) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", provider.getModel());
        body.put("temperature", 0.1);
        body.put("max_tokens", 100);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        /**
         * 您是查询重写助手。将用户口语化的问句转换为适合向量搜索的独立完整查询，
         * 需遵循以下规则：删除代词，补充缺失的背景信息，仅输出重写后的查询内容，无需解释。
         * 用户问句：
         */
        userMsg.put("content", REWRITE_PROMPT + query);
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
