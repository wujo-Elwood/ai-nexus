package com.rag.agent.service;
import com.rag.skill.core.SkillRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.agent.dto.AgentToolChatRequest;
import com.rag.agent.entity.AgentStep;
import com.rag.agent.entity.AgentToolStep;
import com.rag.agent.mapper.AgentInfoMapper;
import com.rag.agent.mapper.AgentStepMapper;
import com.rag.agent.mapper.AgentToolStepMapper;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用工具智能体服务（全流式）
 * 使用自建 OpenAI 兼容流式客户端执行工具调用循环：
 * 每轮流式输出文本增量；本轮流出了工具调用时清空前导文本、执行工具并实时推送步骤，
 * 工具结果回填消息列表后进入下一轮，直到模型给出最终回答。
 * 自建客户端的请求体完全自主，支持 reasoning_effort 等扩展参数
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

    private static final String VISION_PROMPT = "你是 AI Nexus 的视觉智能体。"
            + "仔细观察用户上传的图片，回答关于图片内容、文字和物体的问题，不要凭空编造图里没有的信息。"
            + "如果图中包含需要计算的数字（如金额合计、数量统计），可调用 calculate 工具。回答使用简洁的中文。";

    private static final String DATA_PROMPT = "你是 AI Nexus 的数据问答智能体，可以只读查询系统数据库 rag_db 的业务数据。"
            + "常用表：kb_knowledge_base(知识库)、kb_file(文件)、ai_chat_message(聊天消息)、ai_usage_log(调用统计：model,prompt_tokens,completion_tokens)、ai_image_task(生图任务)、agent_tool_step(工具调用步骤)。"
            + "用户表/权限表/模型供应商配置等含敏感信息的表不允许查询。"
            + "流程：不确定表结构时先调用 list_tables 或 describe_table，再编写 SELECT 查询；只查 rag_db 中的表，"
            + "禁止任何写入操作。数字必须来自查询结果，不要编造；回答用简洁的中文并说明数据口径。";

    private static final String IMAGE_PROMPT = "你是 AI Nexus 的生图智能体，可以调用 generate_image 工具生成 AI 图片。"
            + "把用户的画面需求整理成具体、有画面感的提示词（描述主体、风格、构图、色调等）后再调用工具；"
            + "生成成功后提示用户到「AI 生图」模块的历史记录中查看和下载图片，不要编造图片链接；"
            + "生成失败时说明原因并建议用户调整提示词后重试。回答使用简洁的中文。";

    private static final String DEFAULT_PROMPT = "你是 AI Nexus 的工具智能体。"
            + "你可以调用提供的工具获取实时信息；当问题需要实时数据时必须调用工具，不要凭空编造。"
            + "回答使用简洁的中文，并基于工具返回的真实数据。";

    private final SkillRegistry toolRegistry;
    private final ModelProviderService modelProviderService;
    private final AgentToolStepMapper stepMapper;
    private final AgentStepMapper agentStepMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final ObjectMapper objectMapper;
    private final OpenAiCompatStreamClient streamClient;
    private final Executor chatStreamExecutor;

    public AgentToolService(SkillRegistry toolRegistry,
                            ModelProviderService modelProviderService,
                            AgentToolStepMapper stepMapper,
                            AgentStepMapper agentStepMapper,
                            AgentInfoMapper agentInfoMapper,
                            ObjectMapper objectMapper,
                            OpenAiCompatStreamClient streamClient,
                            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.toolRegistry = toolRegistry;
        this.modelProviderService = modelProviderService;
        this.stepMapper = stepMapper;
        this.agentStepMapper = agentStepMapper;
        this.agentInfoMapper = agentInfoMapper;
        this.objectMapper = objectMapper;
        this.streamClient = streamClient;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /** 创建 SSE 会话并在独立线程中启动智能体流式循环；userId 用于给工具注入当前用户上下文 */
    public SseEmitter chat(AgentToolChatRequest request, Long userId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        emitter.onTimeout(emitter::complete);
        chatStreamExecutor.execute(() -> doChat(request, emitter, userId));
        return emitter;
    }

    private void doChat(AgentToolChatRequest request, SseEmitter emitter, Long userId) {
        String runId = UUID.randomUUID().toString().replace("-", "");
        try {
            ModelProvider provider = modelProviderService.getActive();
            SseEmitterListener listener = new SseEmitterListener(runId, emitter);
            // 配置了步骤的智能体按步骤执行；没配置步骤的仍然是模型自主工具循环
            List<AgentStep> planSteps = loadPlanSteps(request.getAgentCode());
            List<String> allowedTools = planSteps.isEmpty()
                    ? toolRegistry.skillsForAgent(request.getAgentCode())
                    : planSteps.stream()
                            .filter(step -> step.isActive() && isToolStep(step))
                            .map(AgentStep::getToolName)
                            .filter(name -> name != null && !name.isBlank())
                            .distinct()
                            .toList();
            List<Map<String, Object>> tools = toolRegistry.openAiTools(allowedTools);
            listener.onOpen(runId, tools);
            // 推理强度未指定时默认高档
            String reasoningEffort = request.getReasoningEffort() == null || request.getReasoningEffort().isBlank()
                    ? "high" : request.getReasoningEffort();
            AgentModel model = (messages, modelTools, deltas) -> streamClient.chatStream(
                    new OpenAiCompatStreamClient.StreamOptions(
                            normalizeOpenAiBaseUrl(provider.getBaseUrl()),
                            provider.getApiKey(),
                            provider.getModel(),
                            0.3,
                            reasoningEffort,
                            messages,
                            modelTools),
                    deltas);
            String systemPrompt = resolvePrompt(request.getAgentCode());
            if (!planSteps.isEmpty()) {
                runStepPlan(model, request, planSteps, systemPrompt, runId, listener,
                        new AtomicInteger(), userId, request.getAgentCode());
                return;
            }
            runStreamLoop(model, buildMessages(request, systemPrompt),
                    runId, listener, new AtomicInteger(), tools, userId, request.getAgentCode());
        } catch (Exception e) {
            // 把真实失败原因透传给前端（如供应商余额不足、接口不通），避免只看到笼统的重试提示
            log.warn("智能体执行失败: {}", e.getMessage(), e);
            String message = (e.getMessage() == null || e.getMessage().isBlank())
                    ? "智能体执行失败，请稍后重试"
                    : e.getMessage();
            sendEvent(emitter, "error", Map.of("runId", runId, "message", message));
            emitter.complete();
        }
    }

    /**
     * 流式工具调用主循环
     * 每轮流式输出文本增量；本轮流出了工具调用时清空前导文本、执行工具并回填结果，再进入下一轮
     * 包内可见，便于测试时注入假模型
     */
    String runStreamLoop(AgentModel model, List<ChatMessage> messages, String runId,
                         AgentToolListener listener, AtomicInteger stepCounter,
                         List<Map<String, Object>> tools, Long userId, String agentCode) {
        int round = 0;
        while (true) {
            listener.onStatus(round == 0 ? "正在分析问题，决定是否调用工具…" : "正在结合工具结果思考…");
            Response<AiMessage> response;
            try {
                response = model.stream(messages, tools, listener::onAnswerDelta);
            } catch (Exception e) {
                throw new RuntimeException("模型调用失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()), e);
            }
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);
            if (!aiMessage.hasToolExecutionRequests()) {
                listener.onStatus(null);
                String answer = aiMessage.text() == null ? "" : aiMessage.text();
                listener.onAnswer(answer);
                listener.onDone(runId);
                return answer;
            }
            // 本轮流出了工具调用：刚流出的文本只是前导思考，前端需要清空
            listener.onAnswerReset();
            listener.onStatus("正在调用工具…");
            executeToolCalls(messages, runId, listener, aiMessage, stepCounter, userId, agentCode);
            round++;
            if (round >= MAX_ROUNDS) {
                listener.onStatus(null);
                String answer = "已连续调用工具 " + MAX_ROUNDS + " 轮仍未得到最终回答，已停止执行，请调整问题后重试。";
                listener.onAnswer(answer);
                listener.onDone(runId);
                return answer;
            }
        }
    }

    /** 执行本轮全部工具调用：步骤事件、落库、结果回填消息列表 */
    private void executeToolCalls(List<ChatMessage> messages, String runId, AgentToolListener listener,
                                  AiMessage aiMessage, AtomicInteger stepCounter, Long userId, String agentCode) {
        for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
            int stepNo = stepCounter.incrementAndGet();
            listener.onStepStart(stepNo, toolRequest.name(), toolRequest.arguments());
            long start = System.currentTimeMillis();
            String status = "SUCCESS";
            String error = null;
            String result;
            try {
                result = toolRegistry.execute(toolRequest.name(), toolRequest.arguments(), userId);
            } catch (Exception e) {
                status = "FAILED";
                error = e.getMessage();
                result = "工具执行失败：" + (error == null ? "未知错误" : error);
            }
            long costMs = System.currentTimeMillis() - start;
            listener.onStepResult(stepNo, result, costMs, status, error);
            recordStep(runId, agentCode, stepNo, toolRequest, result, status, error, costMs);
            messages.add(new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), result));
        }
    }

    /** 步骤落库，失败不影响对话 */
    private void recordStep(String runId, String agentCode, int stepNo, ToolExecutionRequest toolRequest,
                            String result, String status, String error, long costMs) {
        try {
            AgentToolStep step = new AgentToolStep();
            step.setRunId(runId);
            step.setAgentCode(agentCode);
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

    // ————————————————— 步骤编排执行 —————————————————

    /** 读取智能体的步骤定义；未配置或读取失败时返回空列表，回退模型自主工具循环 */
    private List<AgentStep> loadPlanSteps(String agentCode) {
        if (agentCode == null || agentCode.isBlank()) {
            return List.of();
        }
        try {
            List<AgentStep> steps = agentStepMapper.findByAgent(agentCode);
            if (steps == null) {
                return List.of();
            }
            return steps.stream().filter(AgentStep::isActive).toList();
        } catch (Exception e) {
            log.warn("读取步骤编排失败，回退模型自主工具循环: {}", e.getMessage());
            return List.of();
        }
    }

    /** 是否工具类型步骤（没填类型时按工具处理） */
    private boolean isToolStep(AgentStep step) {
        String type = step.getStepType();
        return type == null || type.isBlank() || AgentStep.TYPE_TOOL.equalsIgnoreCase(type.trim());
    }

    /**
     * 按配置的步骤顺序执行：TOOL 步骤调用技能，LLM 步骤让模型加工中间结果，
     * ANSWER 步骤只作为最终回答的收尾指令；所有步骤完成后流式生成最终回答。
     * 包内可见，便于测试时注入假模型
     */
    String runStepPlan(AgentModel model, AgentToolChatRequest request, List<AgentStep> steps,
                       String systemPrompt, String runId, AgentToolListener listener,
                       AtomicInteger stepCounter, Long userId, String agentCode) {
        String input = request.getMessage() == null ? "" : request.getMessage();
        List<String> stepResults = new ArrayList<>();
        List<String> stepTitles = new ArrayList<>();
        String answerInstruction = null;
        listener.onStatus("正在按配置的步骤执行…");
        for (AgentStep step : steps) {
            String type = step.getStepType() == null ? AgentStep.TYPE_TOOL : step.getStepType().trim().toUpperCase();
            // 收尾步骤不单独执行，只作为最终回答的附加要求
            if (AgentStep.TYPE_ANSWER.equals(type)) {
                answerInstruction = step.getInstruction();
                continue;
            }
            int stepNo = stepCounter.incrementAndGet();
            String title = step.displayName();
            long start = System.currentTimeMillis();
            String status = "SUCCESS";
            String error = null;
            String result;
            String invokedName;
            String argumentsOrInstruction;
            if (isToolStep(step)) {
                invokedName = step.getToolName();
                argumentsOrInstruction = buildToolArguments(step.getArgsTemplate(), input, stepResults);
                listener.onStepStart(stepNo, invokedName, argumentsOrInstruction);
                try {
                    if (invokedName == null || invokedName.isBlank()) {
                        throw new BusinessException(400, "该步骤没有配置技能");
                    }
                    result = toolRegistry.execute(invokedName, argumentsOrInstruction, userId);
                } catch (Exception e) {
                    status = "FAILED";
                    error = e.getMessage() == null ? "未知错误" : e.getMessage();
                    result = "步骤执行失败：" + error;
                }
            } else {
                invokedName = "model";
                argumentsOrInstruction = step.getInstruction() == null ? "" : step.getInstruction();
                listener.onStepStart(stepNo, invokedName, argumentsOrInstruction);
                try {
                    result = callModelOnce(model,
                            buildStepMessages(systemPrompt, argumentsOrInstruction, input, stepTitles, stepResults));
                    if (result.isBlank()) {
                        throw new IllegalStateException("模型未返回内容");
                    }
                } catch (Exception e) {
                    status = "FAILED";
                    error = e.getMessage() == null ? "未知错误" : e.getMessage();
                    result = "步骤执行失败：" + error;
                }
            }
            long costMs = System.currentTimeMillis() - start;
            listener.onStepResult(stepNo, result, costMs, status, error);
            recordStep(runId, agentCode, stepNo, invokedName, argumentsOrInstruction, result, status, error, costMs);
            stepResults.add(result);
            stepTitles.add(title);
            // 任一步骤失败即终止，避免带着错误的中间结果继续调用模型
            if ("FAILED".equals(status)) {
                listener.onStatus(null);
                String answer = "第 " + stepNo + " 步「" + title + "」执行失败：" + error
                        + "\n已终止后续步骤，请检查步骤配置或调整问题后重试。";
                listener.onAnswer(answer);
                listener.onDone(runId);
                return answer;
            }
        }
        // 所有步骤完成：让模型把步骤结果整理成最终回答（流式输出到前端）
        listener.onStatus("正在整理最终回答…");
        List<ChatMessage> finalMessages = new ArrayList<>();
        finalMessages.add(SystemMessage.systemMessage(systemPrompt));
        finalMessages.add(UserMessage.userMessage(
                buildFinalPrompt(input, stepTitles, stepResults, answerInstruction)));
        String answer;
        try {
            AiMessage finalMessage = model.stream(finalMessages, null, listener::onAnswerDelta).content();
            answer = finalMessage == null || finalMessage.text() == null ? "" : finalMessage.text();
        } catch (Exception e) {
            // 步骤都跑完了，只是最后一次整理失败：直接把步骤结果回显给用户，避免整轮白跑
            log.warn("整理最终回答失败，改为直接回显步骤结果: {}", e.getMessage());
            answer = buildFallbackAnswer(stepTitles, stepResults);
        }
        listener.onStatus(null);
        listener.onAnswer(answer);
        listener.onDone(runId);
        return answer;
    }

    /** 最终整理失败时的兜底回答：按步骤顺序拼接已有结果 */
    private String buildFallbackAnswer(List<String> stepTitles, List<String> stepResults) {
        if (stepResults.isEmpty()) {
            return "本次没有执行任何步骤，请检查智能体的步骤配置。";
        }
        StringBuilder answer = new StringBuilder("已完成以下步骤（整理最终回答失败，直接展示步骤结果）：\n");
        for (int i = 0; i < stepResults.size(); i++) {
            answer.append("\n第 ").append(i + 1).append(" 步（").append(stepTitles.get(i)).append("）：\n")
                    .append(truncate(stepResults.get(i), 1500)).append("\n");
        }
        return answer.toString();
    }

    /** 不携带工具的一次模型调用，用于 LLM 中间步骤（只要文本结果） */
    private String callModelOnce(AgentModel model, List<ChatMessage> messages) throws Exception {
        Response<AiMessage> response = model.stream(messages, null, delta -> { });
        AiMessage message = response == null ? null : response.content();
        return message == null || message.text() == null ? "" : message.text();
    }

    /** 组装 LLM 步骤的消息：系统提示 + 本步指令 + 用户请求 + 前置步骤结果 */
    private List<ChatMessage> buildStepMessages(String systemPrompt, String instruction, String input,
                                                List<String> stepTitles, List<String> stepResults) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(systemPrompt));
        StringBuilder prompt = new StringBuilder();
        prompt.append(instruction).append("\n\n用户请求：\n").append(input);
        if (!stepResults.isEmpty()) {
            prompt.append("\n\n已完成的前置步骤结果：\n");
            for (int i = 0; i < stepResults.size(); i++) {
                prompt.append("第 ").append(i + 1).append(" 步（").append(stepTitles.get(i)).append("）：\n")
                        .append(truncate(stepResults.get(i), 4000)).append("\n\n");
            }
        }
        prompt.append("\n只输出本步骤需要的结果，不要编造未提供的信息。");
        messages.add(UserMessage.userMessage(prompt.toString()));
        return messages;
    }

    /** 组装最终回答的消息：用户请求 + 每步结果 + 收尾指令 */
    private String buildFinalPrompt(String input, List<String> stepTitles, List<String> stepResults,
                                    String answerInstruction) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户请求：\n").append(input).append("\n\n已执行的步骤与结果：\n");
        if (stepResults.isEmpty()) {
            prompt.append("（没有需要执行的步骤）\n");
        } else {
            for (int i = 0; i < stepResults.size(); i++) {
                prompt.append("第 ").append(i + 1).append(" 步（").append(stepTitles.get(i)).append("）：\n")
                        .append(truncate(stepResults.get(i), 4000)).append("\n\n");
            }
        }
        prompt.append("请基于以上步骤结果，用简洁的中文给出最终回答，不要编造步骤结果中没有的信息。");
        if (answerInstruction != null && !answerInstruction.isBlank()) {
            prompt.append("\n额外要求：").append(answerInstruction.trim());
        }
        return prompt.toString();
    }

    /**
     * 渲染工具参数模板：${input} 替换成用户问题，${step1}/${step2} 替换成对应步骤的结果。
     * 模板为空时退化成空对象，保证工具至少收到合法 JSON
     */
    String buildToolArguments(String template, String input, List<String> stepResults) {
        if (template == null || template.isBlank()) {
            return "{}";
        }
        String arguments = template.replace("${input}", escapeJson(input));
        for (int i = 0; i < stepResults.size(); i++) {
            arguments = arguments.replace("${step" + (i + 1) + "}", escapeJson(stepResults.get(i)));
        }
        // 清掉没有对应结果的占位符，避免把 ${xxx} 原样发给工具
        return arguments.replaceAll("\\$\\{[^}]*}", "");
    }

    /** 转义成可以安全放进 JSON 字符串的值 */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    /** 截断过长的步骤结果，避免把整段 HTML/长文塞进下一次模型调用 */
    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…（已截断）";
    }

    /** 步骤落库（步骤编排版本），失败不影响对话 */
    private void recordStep(String runId, String agentCode, int stepNo, String toolName, String arguments,
                            String result, String status, String error, long costMs) {
        try {
            AgentToolStep step = new AgentToolStep();
            step.setRunId(runId);
            step.setAgentCode(agentCode);
            step.setStepNo(stepNo);
            step.setToolName(toolName == null || toolName.isBlank() ? "model" : toolName);
            step.setArguments(arguments);
            step.setResult(result);
            step.setStatus(status);
            step.setErrorMessage(error);
            step.setCostMs(costMs);
            stepMapper.insert(step);
        } catch (Exception e) {
            log.warn("编排步骤落库失败: {}", e.getMessage());
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
                    throw new BusinessException(400, "图片过大，请压缩后重试（单张不超过约 11MB）");
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

    /** 把 dataURL 或裸 base64 转成 langchain4j 图片内容 */
    private dev.langchain4j.data.message.ImageContent toImageContent(String image) {
        if (image.startsWith("data:")) {
            int semi = image.indexOf(';');
            int comma = image.indexOf(',');
            String mime = semi > 5 ? image.substring(5, semi) : "image/png";
            return dev.langchain4j.data.message.ImageContent.from(image.substring(comma + 1), mime);
        }
        return dev.langchain4j.data.message.ImageContent.from(image, "image/png");
    }

    /** 按智能体编码选择系统提示词：优先数据库配置（智能体管理页可编辑），无配置时回退内置提示词 */
    private String resolvePrompt(String agentCode) {
        // 第1步：读取智能体注册表中配置的提示词
        try {
            String prompt = agentInfoMapper.findPromptByCode(agentCode);
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        } catch (Exception e) {
            // 注册表不可用时回退内置提示词
        }
        // 第2步：内置智能体的固定提示词
        if ("currency-converter".equals(agentCode)) {
            return CURRENCY_PROMPT;
        }
        if ("vision-agent".equals(agentCode)) {
            return VISION_PROMPT;
        }
        if ("data-agent".equals(agentCode)) {
            return DATA_PROMPT;
        }
        if ("image-agent".equals(agentCode)) {
            return IMAGE_PROMPT;
        }
        if ("general-tool".equals(agentCode)) {
            return WEATHER_PROMPT;
        }
        return DEFAULT_PROMPT;
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
        public void onStepResult(int stepNo, String result, long costMs, String status, String errorMessage) {
            sendEvent(emitter, "step_result", Map.of(
                    "runId", runId,
                    "stepNo", stepNo,
                    "result", result,
                    "costMs", costMs,
                    "status", status,
                    "errorMessage", errorMessage == null ? "" : errorMessage));
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
