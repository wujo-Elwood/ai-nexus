package com.rag.ai;

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
import java.util.ArrayList;
import java.util.List;

/**
 * 追问建议生成器
 * 基于刚完成的问答生成用户可能继续追问的问题，随 SSE 在 done 前下发；
 * 生成失败时返回空列表，不影响正常回答流程
 */
@Slf4j
@Component
public class FollowupSuggester {

    /** 最多生成的追问条数 */
    private static final int MAX_SUGGESTIONS = 3;
    /** 单条追问长度上限，过长的问题不适合做按钮 */
    private static final int MAX_SUGGESTION_LENGTH = 60;

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PROMPT =
            "基于下面的用户问题和助手回答，生成 3 个用户最可能继续追问的问题。"
            + "要求：与原问题相关但不重复、简短具体、每条不超过 30 个字。"
            + "只输出 3 行，每行一个问题，不要编号、不要引号、不要任何解释。\n\n"
            + "用户问题：";

    public FollowupSuggester(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    /**
     * 生成追问建议
     *
     * @param question 用户问题
     * @param answer   助手回答（过长时截断，节省 token）
     * @return 追问列表（最多 3 条），失败返回空列表
     */
    public List<String> suggest(String question, String answer) {
        List<String> suggestions = new ArrayList<>();
        try {
            ModelProvider provider = modelProviderService.getActive();
            String answerSnippet = answer == null ? "" : answer.substring(0, Math.min(answer.length(), 1500));
            String requestBody = buildRequest(question, answerSnippet, provider);
            HttpURLConnection conn = openConnection(buildEndpoint(provider), provider);
            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
            if (conn.getResponseCode() >= 400) {
                conn.disconnect();
                return suggestions;
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();
            String content = objectMapper.readTree(response.toString())
                    .get("choices").get(0).get("message").get("content").asText();
            suggestions.addAll(parseSuggestions(content));
        } catch (Exception e) {
            // 追问建议是锦上添花，任何失败都静默降级为无建议
            log.warn("生成追问建议失败: {}", e.getMessage());
        }
        return suggestions;
    }

    /** 解析模型输出：优先按 JSON 数组解析，失败时退回逐行提取 */
    List<String> parseSuggestions(String content) {
        List<String> suggestions = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return suggestions;
        }
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\s*", "").replaceAll("```$", "").trim();
        }
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonNode array = objectMapper.readTree(cleaned.substring(start, end + 1));
                if (array.isArray()) {
                    for (JsonNode item : array) {
                        addSuggestion(suggestions, item.asText());
                    }
                }
                return suggestions;
            } catch (Exception e) {
                // 数组解析失败退回逐行提取
            }
        }
        // 单行且非 JSON 输出大概率是模型话术，不能当追问
        boolean jsonLike = start >= 0;
        for (String line : cleaned.split("\n")) {
            String text = line.replaceAll("^\\s*\\d+[.、)）]?\\s*", "").trim();
            text = text.replace("[", " ").replace("]", " ")
                    .replace("\"", " ").replace("'", " ")
                    .replace("，", " ").replace(",", " ").replace("、", " ")
                    .trim();
            addSuggestion(suggestions, text);
        }
        if (!jsonLike && suggestions.size() < 2 && cleaned.lines().count() < 2) {
            return new ArrayList<>();
        }
        return suggestions;
    }

    /** 去引号、限长、去重后加入候选 */
    private void addSuggestion(List<String> suggestions, String raw) {
        if (raw == null) {
            return;
        }
        String text = raw.replace("[", " ").replace("]", " ")
                .replace("\"", " ").replace("'", " ")
                .replace("，", " ").replace(",", " ").replace("、", " ")
                .trim();
        if (text.isEmpty() || text.length() > MAX_SUGGESTION_LENGTH || suggestions.contains(text)) {
            return;
        }
        suggestions.add(text);
    }

private String buildRequest(String question, String answerSnippet, ModelProvider provider) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", provider.getModel());
        body.put("temperature", 0.7);
        body.put("max_tokens", 300);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", PROMPT + question + "\n\n助手回答：" + answerSnippet);
        return objectMapper.writeValueAsString(body);
    }

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
        conn.setReadTimeout(15000);
        return conn;
    }
}
