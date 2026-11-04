package com.rag.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 轻量 OpenAI 兼容流式客户端（智能体模块专用）
 * 不依赖 LangChain4j 的模型封装，请求体完全自主——支持 reasoning_effort 等扩展参数；
 * 以阻塞方式执行：文本增量在读取过程中实时回调，方法返回时即为完整响应
 */
@Component
public class OpenAiCompatStreamClient {

    private final ObjectMapper objectMapper;
    /** 强制 HTTP/1.1：长连接复用的 HTTP/2 在代理/服务端空闲回收时会以 GOAWAY 掐断进行中的流式回答 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpenAiCompatStreamClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 一次流式对话的全部入参 */
    public record StreamOptions(
            String baseUrl,
            String apiKey,
            String model,
            double temperature,
            String reasoningEffort,
            List<ChatMessage> messages,
            List<Map<String, Object>> tools) {
    }

    /**
     * 执行流式对话：文本增量实时回调 deltaConsumer；返回完整响应（可能含工具调用请求）
     *
     * @throws java.io.IOException 网络/协议错误时抛出，message 为可读错误信息
     */
    public Response<AiMessage> chatStream(StreamOptions options, java.util.function.Consumer<String> deltaConsumer)
            throws IOException, InterruptedException {
        // 第1步：拼装请求体
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("model", options.model());
        body.put("stream", true);
        body.put("temperature", options.temperature());
        if (options.reasoningEffort() != null && !options.reasoningEffort().isBlank()) {
            body.put("reasoning_effort", options.reasoningEffort());
        }
        body.set("messages", serializeMessages(options.messages()));
        if (options.tools() != null && !options.tools().isEmpty()) {
            body.set("tools", objectMapper.valueToTree(options.tools()));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(options.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + options.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        // 第2步：发送并逐行解析 SSE
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("模型接口返回 " + response.statusCode() + "：" + extractMessage(error));
        }

        StringBuilder content = new StringBuilder();
        Map<Integer, StringBuilder> toolIds = new LinkedHashMap<>();
        Map<Integer, String> toolNames = new LinkedHashMap<>();
        Map<Integer, StringBuilder> toolArguments = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode chunk = objectMapper.readTree(payload);
                JsonNode delta = chunk.path("choices").path(0).path("delta");
                String contentPart = delta.path("content").asText(null);
                if (contentPart != null && !contentPart.isEmpty()) {
                    content.append(contentPart);
                    deltaConsumer.accept(contentPart);
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        int index = call.path("index").asInt(0);
                        toolIds.computeIfAbsent(index, key -> new StringBuilder()).append(call.path("id").asText(""));
                        String name = call.path("function").path("name").asText("");
                        if (!name.isEmpty()) {
                            toolNames.put(index, name);
                        }
                        toolArguments.computeIfAbsent(index, key -> new StringBuilder())
                                .append(call.path("function").path("arguments").asText(""));
                    }
                }
            }
        }

        // 第3步：组装最终响应
        if (!toolNames.isEmpty()) {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (Integer index : new TreeSet<>(toolNames.keySet())) {
                requests.add(ToolExecutionRequest.builder()
                        .id(toolIds.getOrDefault(index, new StringBuilder()).toString())
                        .name(toolNames.get(index))
                        .arguments(toolArguments.getOrDefault(index, new StringBuilder()).toString())
                        .build());
            }
            return Response.from(AiMessage.from(requests.toArray(new ToolExecutionRequest[0])));
        }
        return Response.from(AiMessage.from(content.toString()));
    }

    /** 把 langchain4j 消息列表序列化为 OpenAI 格式 */
    private com.fasterxml.jackson.databind.node.ArrayNode serializeMessages(List<ChatMessage> messages) {
        com.fasterxml.jackson.databind.node.ArrayNode array = objectMapper.createArrayNode();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                array.add(objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", systemMessage.text()));
            } else if (message instanceof UserMessage userMessage) {
                array.add(serializeUserMessage(userMessage));
            } else if (message instanceof AiMessage aiMessage) {
                array.add(serializeAiMessage(aiMessage));
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                array.add(objectMapper.createObjectNode()
                        .put("role", "tool")
                        .put("tool_call_id", toolResult.id())
                        .put("content", toolResult.text()));
            }
        }
        return array;
    }

    /** 用户消息：纯文本用字符串，带图片时用 多模态数组 */
    private com.fasterxml.jackson.databind.node.ObjectNode serializeUserMessage(UserMessage userMessage) {
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("role", "user");
        List<dev.langchain4j.data.message.Content> contents = userMessage.contents();
        boolean singleText = contents.size() == 1 && contents.get(0) instanceof TextContent;
        if (singleText) {
            node.put("content", ((TextContent) contents.get(0)).text());
            return node;
        }
        com.fasterxml.jackson.databind.node.ArrayNode parts = node.putArray("content");
        for (dev.langchain4j.data.message.Content content : contents) {
            if (content instanceof TextContent textContent) {
                parts.addObject().put("type", "text").put("text", textContent.text());
            } else if (content instanceof ImageContent imageContent) {
                parts.addObject()
                        .put("type", "image_url")
                        .set("image_url", objectMapper.createObjectNode().put("url", toImageUrl(imageContent.image())));
            }
        }
        return node;
    }

    /** 助手消息：纯文本用字符串，有工具调用时带 tool_calls */
    private com.fasterxml.jackson.databind.node.ObjectNode serializeAiMessage(AiMessage aiMessage) {
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("role", "assistant");
        String text = aiMessage.text();
        if (text != null && !text.isEmpty()) {
            node.put("content", text);
        }
        if (aiMessage.hasToolExecutionRequests()) {
            com.fasterxml.jackson.databind.node.ArrayNode calls = node.putArray("tool_calls");
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                calls.addObject()
                        .put("id", toolRequest.id())
                        .put("type", "function")
                        .set("function", objectMapper.createObjectNode()
                                .put("name", toolRequest.name())
                                .put("arguments", toolRequest.arguments() == null ? "" : toolRequest.arguments()));
            }
        }
        return node;
    }

    /** 图片内容还原为 URL（base64 转数据链接，或直接使用地址） */
    private String toImageUrl(dev.langchain4j.data.image.Image image) {
        if (image.base64Data() != null && !image.base64Data().isEmpty()) {
            String mime = image.mimeType() == null ? "image/png" : image.mimeType();
            return "data:" + mime + ";base64," + image.base64Data();
        }
        return image.url() == null ? "" : image.url().toString();
    }

    private String stripTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    /** 从错误响应体里提取可读信息 */
    private String extractMessage(String errorBody) {
        try {
            JsonNode node = objectMapper.readTree(errorBody);
            String message = node.path("error").path("message").asText("");
            if (message.isEmpty()) {
                message = node.path("message").asText("");
            }
            return message.isEmpty() ? errorBody : message;
        } catch (Exception e) {
            return errorBody;
        }
    }
}
