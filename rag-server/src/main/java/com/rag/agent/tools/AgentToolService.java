package com.rag.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.agent.dto.AgentToolChatRequest;
import com.rag.agent.entity.AgentToolStep;
import com.rag.agent.mapper.AgentToolStepMapper;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 通用工具智能体服务
 * 使用低阶 ChatLanguageModel API 手写工具调用循环，从而在每一步插入监听回调：
 * 模型发起 tool_calls 时实时通知前端并落库，工具结果回填后再进入下一轮，直到模型给出最终回答
 */
@Slf4j
@Service
public class AgentToolService {

    /** 最大工具调用轮数，防止模型死循环 */
    static final int MAX_ROUNDS = 6;

    private static final String SYSTEM_PROMPT = "你是 AI Nexus 的通用工具智能体。"
            + "你可以调用提供的工具获取实时信息；当问题需要实时数据时必须调用工具，不要凭空编造。"
            + "回答使用简洁的中文，并基于工具返回的真实数据。";

    private final ToolRegistry toolRegistry;
    private final ModelProviderService modelProviderService;
    private final AgentToolStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor chatStreamExecutor;

    public AgentToolService(ToolRegistry toolRegistry,
                            ModelProviderService modelProviderService,
                            AgentToolStepMapper stepMapper,
                            ObjectMapper objectMapper,
                            @Qualifier("chatStreamExecutor") ThreadPoolExecutor chatStreamExecutor) {
        this.toolRegistry = toolRegistry;
        this.modelProviderService = modelProviderService;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /** 创建 SSE 会话并在独立线程中执行智能体循环 */
    public SseEmitter chat(AgentToolChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(emitter::complete);
        chatStreamExecutor.execute(() -> doChat(request, emitter));
        return emitter;
    }

    private void doChat(AgentToolChatRequest request, SseEmitter emitter) {
        String runId = UUID.randomUUID().toString().replace("-", "");
        try {
            ModelProvider provider = modelProviderService.getActive();
            ChatLanguageModel model = buildChatModel(provider);
            SseEmitterListener listener = new SseEmitterListener(runId, emitter);
            listener.onOpen(runId, toolRegistry.describe());
            runLoop(model, buildMessages(request), runId, listener);
            listener.onDone(runId);
        } catch (Exception e) {
            // 把真实失败原因透传给前端（如供应商余额不足、接口不通），避免只看到笼统的重试提示
            log.warn("通用工具智能体执行失败: {}", e.getMessage(), e);
            String message = (e.getMessage() == null || e.getMessage().isBlank())
                    ? "智能体执行失败，请稍后重试"
                    : e.getMessage();
            sendEvent(emitter, "error", Map.of("runId", runId, "message", message));
            emitter.complete();
        }
    }

    /**
     * 工具调用主循环：模型返回 tool_calls 就执行工具并回填结果，直到给出最终回答
     * 包内可见，便于测试时注入假模型
     */
    String runLoop(ChatLanguageModel model, List<ChatMessage> messages, String runId, AgentToolListener listener) {
        List<ToolSpecification> tools = toolRegistry.specifications();
        Response<AiMessage> response = model.generate(messages, tools);
        int stepNo = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);
            if (!aiMessage.hasToolExecutionRequests()) {
                String answer = aiMessage.text() == null ? "" : aiMessage.text();
                listener.onAnswer(answer);
                return answer;
            }
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                stepNo++;
                listener.onStepStart(stepNo, toolRequest.name(), toolRequest.arguments());
                long start = System.currentTimeMillis();
                String status = "SUCCESS";
                String error = null;
                String result;
                try {
                    result = toolRegistry.execute(toolRequest.name(), toolRequest.arguments());
                } catch (Exception e) {
                    status = "FAILED";
                    error = e.getMessage();
                    result = "工具执行失败：" + (error == null ? "未知错误" : error);
                }
                long costMs = System.currentTimeMillis() - start;
                listener.onStepResult(stepNo, result, costMs, status);
                recordStep(runId, stepNo, toolRequest, result, status, error, costMs);
                messages.add(new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), result));
            }
            response = model.generate(messages, tools);
        }
        String answer = "已连续调用工具 " + MAX_ROUNDS + " 轮仍未得到最终回答，已停止执行，请调整问题后重试。";
        listener.onAnswer(answer);
        return answer;
    }

    /** 步骤落库，失败不影响对话 */
    private void recordStep(String runId, int stepNo, ToolExecutionRequest toolRequest,
                            String result, String status, String error, long costMs) {
        try {
            AgentToolStep step = new AgentToolStep();
            step.setRunId(runId);
            step.setStepNo(stepNo);
            step.setToolName(toolRequest.name());
            step.setArguments(toolRequest.arguments());
            step.setResult(result);
            step.setStatus(status);
            step.setErrorMessage(error);
            step.setCostMs(costMs);
            stepMapper.insert(step);
        } catch (Exception e) {
            log.warn("工具调用步骤落库失败: {}", e.getMessage());
        }
    }

    /** 组装消息列表：系统提示 + 历史对话（最多保留 20 条）+ 本次输入 */
    private List<ChatMessage> buildMessages(AgentToolChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(SYSTEM_PROMPT));
        if (request.getHistory() != null && !request.getHistory().isEmpty()) {
            List<AgentToolChatRequest.HistoryTurn> history = request.getHistory();
            int from = Math.max(0, history.size() - 20);
            for (AgentToolChatRequest.HistoryTurn turn : history.subList(from, history.size())) {
                if (turn.getContent() == null || turn.getContent().isBlank()) {
                    continue;
                }
                if ("assistant".equalsIgnoreCase(turn.getRole())) {
                    messages.add(AiMessage.from(turn.getContent()));
                } else {
                    messages.add(UserMessage.userMessage(turn.getContent()));
                }
            }
        }
        messages.add(UserMessage.userMessage(request.getMessage()));
        return messages;
    }

    /** 按激活供应商创建同步聊天模型，与聊天服务保持一致的地址归一化规则 */
    private ChatLanguageModel buildChatModel(ModelProvider provider) {
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** 规范化 OpenAI 兼容接口地址，兼容用户填写完整 chat/completions 地址 */
    private String normalizeOpenAiBaseUrl(String rawBaseUrl) {
        String baseUrl = rawBaseUrl.replaceAll("/+$", "");
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl;
        }
        if (!baseUrl.matches(".*/v\\d+(?:/.*)?$")) {
            return baseUrl + "/v1";
        }
        return baseUrl;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.debug("SSE 发送失败（连接可能已关闭）: {}", e.getMessage());
        }
    }

    /** 监听器实现：把每一步事件实时推送到 SSE */
    private class SseEmitterListener implements AgentToolListener {

        private final String runId;
        private final SseEmitter emitter;

        SseEmitterListener(String runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }

        @Override
        public void onOpen(String runId, Object tools) {
            sendEvent(emitter, "open", Map.of("runId", runId, "tools", tools));
        }

        @Override
        public void onStepStart(int stepNo, String toolName, String arguments) {
            sendEvent(emitter, "step_start", Map.of(
                    "runId", runId,
                    "stepNo", stepNo,
                    "tool", toolName,
                    "arguments", arguments == null ? "" : arguments));
        }

        @Override
        public void onStepResult(int stepNo, String result, long costMs, String status) {
            sendEvent(emitter, "step_result", Map.of(
                    "runId", runId,
                    "stepNo", stepNo,
                    "result", result,
                    "costMs", costMs,
                    "status", status));
        }

        @Override
        public void onAnswer(String content) {
            sendEvent(emitter, "answer", Map.of("runId", runId, "content", content));
        }

        @Override
        public void onDone(String runId) {
            sendEvent(emitter, "done", Map.of("runId", runId));
            emitter.complete();
        }

        @Override
        public void onError(String message) {
            sendEvent(emitter, "error", Map.of("runId", runId, "message", message));
        }
    }
}
