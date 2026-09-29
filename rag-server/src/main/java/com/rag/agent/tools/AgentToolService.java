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
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用工具智能体服务（全流式）
 * 使用低阶 StreamingChatLanguageModel API 手写工具调用循环：
 * 模型文本逐字流式推送给前端；本轮流出了工具调用时清空前导文本、执行工具并实时推送步骤，
 * 工具结果回填消息列表后进入下一轮，直到模型给出最终回答
 */
@Slf4j
@Service
public class AgentToolService {

    /** 最大工具调用轮数，防止模型死循环 */
    static final int MAX_ROUNDS = 10;

    /** 单张图片 base64 大小上限（约 11MB，对应原始图片 10MB） */
    private static final int MAX_IMAGE_LENGTH = 15_000_000;
    /** 单次最多携带图片数 */
    private static final int MAX_IMAGES = 4;

    private static final String WEATHER_PROMPT = "你是 AI Nexus 的天气查询智能体。"
            + "回答天气相关问题前必须先调用 search_city_location 获取城市坐标，再用 get_weather 查询天气，不要凭空编造数据。"
            + "回答使用简洁的中文，并基于工具返回的真实数据。";

    private static final String CURRENCY_PROMPT = "你是 AI Nexus 的货币换算智能体。"
            + "换算前必须先用 get_exchange_rate 查询实时汇率，再用 calculate 计算金额，不要凭空编造数据。"
            + "币种使用 3 位 ISO 代码（如 CNY、USD、EUR、JPY）。回答用简洁的中文，说明换算过程和结果。";

    private static final String DEFAULT_PROMPT = "你是 AI Nexus 的工具智能体。"
            + "你可以调用提供的工具获取实时信息；当问题需要实时数据时必须调用工具，不要凭空编造。"
            + "回答使用简洁的中文，并基于工具返回的真实数据。";

    private static final String VISION_PROMPT = "你是 AI Nexus 的视觉智能体。"
            + "仔细观察用户上传的图片，回答关于图片内容、文字和物体的问题，不要凭空编造图里没有的信息。"
            + "如果图中包含需要计算的数字（如金额合计、数量统计），可调用 calculate 工具。回答使用简洁的中文。";

    private final ToolRegistry toolRegistry;
    private final ModelProviderService modelProviderService;
    private final AgentToolStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final Executor chatStreamExecutor;

    public AgentToolService(ToolRegistry toolRegistry,
                            ModelProviderService modelProviderService,
                            AgentToolStepMapper stepMapper,
                            ObjectMapper objectMapper,
                            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.toolRegistry = toolRegistry;
        this.modelProviderService = modelProviderService;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /** 创建 SSE 会话并在独立线程中启动智能体流式循环 */
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
            StreamingChatLanguageModel model = buildStreamingModel(provider);
            SseEmitterListener listener = new SseEmitterListener(runId, emitter);
            List<String> allowedTools = toolRegistry.toolsForAgent(request.getAgentCode());
            listener.onOpen(runId, toolRegistry.describe(allowedTools));
            runStreamLoop(model, buildMessages(request, resolvePrompt(request.getAgentCode())), runId, listener,
                    0, new AtomicInteger(), toolRegistry.specifications(allowedTools));
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
     * 流式工具调用主循环
     * 每轮流式输出文本增量；onComplete 时如果本轮流出了工具调用，则清空前导文本、
     * 在执行线程中执行工具并回填结果，再递归进入下一轮
     * 包内可见，便于测试时注入假模型
     */
    void runStreamLoop(StreamingChatLanguageModel model, List<ChatMessage> messages, String runId,
                       AgentToolListener listener, int round, AtomicInteger stepCounter,
                       List<ToolSpecification> tools) {
        listener.onStatus(round == 0 ? "正在分析问题，决定是否调用工具…" : "正在结合工具结果思考…");
        model.generate(messages, tools, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                listener.onAnswerDelta(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                AiMessage aiMessage = response.content();
                messages.add(aiMessage);
                if (!aiMessage.hasToolExecutionRequests()) {
                    String answer = aiMessage.text() == null ? "" : aiMessage.text();
                    listener.onStatus(null);
                    listener.onAnswer(answer);
                    listener.onDone(runId);
                    return;
                }
                // 本轮流出了工具调用：刚流出的文本只是前导思考，前端需要清空
                listener.onAnswerReset();
                listener.onStatus("正在调用工具…");
                // 工具执行可能耗时（HTTP 调用、落库），放回执行线程，不阻塞模型客户端回调线程
                chatStreamExecutor.execute(() -> {
                    try {
                        executeToolCalls(messages, runId, listener, aiMessage, stepCounter);
                        if (round + 1 >= MAX_ROUNDS) {
                            listener.onStatus(null);
                            listener.onAnswer("已连续调用工具 " + MAX_ROUNDS + " 轮仍未得到最终回答，已停止执行，请调整问题后重试。");
                            listener.onDone(runId);
                            return;
                        }
                        runStreamLoop(model, messages, runId, listener, round + 1, stepCounter, tools);
                    } catch (Exception e) {
                        log.warn("通用工具智能体工具执行失败: {}", e.getMessage(), e);
                        listener.onError(e.getMessage() == null || e.getMessage().isBlank() ? "工具执行失败" : e.getMessage());
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                log.warn("通用工具智能体流式调用失败: {}", error.getMessage(), error);
                listener.onError(error.getMessage() == null || error.getMessage().isBlank()
                        ? "模型调用失败，请稍后重试"
                        : error.getMessage());
            }
        });
    }

    /** 执行本轮全部工具调用：步骤事件、落库、结果回填消息列表 */
    private void executeToolCalls(List<ChatMessage> messages, String runId, AgentToolListener listener,
                                  AiMessage aiMessage, AtomicInteger stepCounter) {
        for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
            int stepNo = stepCounter.incrementAndGet();
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

    /** 组装消息列表：系统提示 + 历史对话（最多保留 20 条）+ 本次输入（可携带图片，构成多模态消息） */
    List<ChatMessage> buildMessages(AgentToolChatRequest request, String systemPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(systemPrompt));
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
        String text = request.getMessage();
        List<String> images = request.getImages();
        if (images != null && !images.isEmpty()) {
            // 有图片时构造多模态消息：文本 + 每张图片一个 ImageContent
            List<dev.langchain4j.data.message.Content> parts = new ArrayList<>();
            parts.add(dev.langchain4j.data.message.TextContent.from(text));
            int count = 0;
            for (String image : images) {
                if (image == null || image.isBlank() || count >= MAX_IMAGES) {
                    continue;
                }
                if (image.length() > MAX_IMAGE_LENGTH) {
                    throw new BusinessException(400, "图片过大，请压缩后重试（单张不超过约 5MB）");
                }
                parts.add(toImageContent(image));
                count++;
            }
            messages.add(new UserMessage(parts));
        } else {
            messages.add(UserMessage.userMessage(text));
        }
        return messages;
    }

    /** 把 dataURL 或裸 base64 转成 LangChain4j 图片内容 */
    private dev.langchain4j.data.message.ImageContent toImageContent(String image) {
        if (image.startsWith("data:")) {
            int semi = image.indexOf(';');
            int comma = image.indexOf(',');
            String mime = (semi > 5 ? image.substring(5, semi) : "image/png");
            return dev.langchain4j.data.message.ImageContent.from(image.substring(comma + 1), mime);
        }
        return dev.langchain4j.data.message.ImageContent.from(image, "image/png");
    }

    /** 按智能体编码选择系统提示词 */
    private String resolvePrompt(String agentCode) {
        if ("currency-converter".equals(agentCode)) {
            return CURRENCY_PROMPT;
        }
        if ("vision-agent".equals(agentCode)) {
            return VISION_PROMPT;
        }
        if ("general-tool".equals(agentCode)) {
            return WEATHER_PROMPT;
        }
        return DEFAULT_PROMPT;
    }

    /** 按激活供应商创建流式聊天模型，与聊天服务保持一致的地址归一化规则 */
    private StreamingChatLanguageModel buildStreamingModel(ModelProvider provider) {
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        return OpenAiStreamingChatModel.builder()
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

    /** 监听器实现：把状态、步骤、流式文本实时推送到 SSE */
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
        public void onStatus(String message) {
            sendEvent(emitter, "status", Map.of("message", message == null ? "" : message));
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
        public void onAnswerDelta(String delta) {
            sendEvent(emitter, "answer_delta", Map.of("delta", delta == null ? "" : delta));
        }

        @Override
        public void onAnswerReset() {
            sendEvent(emitter, "answer_reset", Map.of("runId", runId));
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
            emitter.complete();
        }
    }
}
