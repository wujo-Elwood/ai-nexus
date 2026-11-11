package com.rag.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.ModelProvider;
import com.rag.entity.UsageLog;
import com.rag.mapper.ChatMessageMapper;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.mapper.AnswerQualityMapper;
import com.rag.rag.*;
import com.rag.rag.QdrantService.SearchResult;
import com.rag.service.ModelProviderService;
import com.rag.service.UsageService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 聊天服务
 * 核心职责：多轮对话管理、RAG 检索增强、大模型调用、用量记录
 *
 * 工作流程：
 * 1. 安全检查（Prompt 注入防护）
 * 2. 保存用户消息到数据库
 * 3. 查询改写 + 混合检索 + 重排序 + 缓存 → 构建知识库上下文（含来源信息）
 * 4. 加载历史对话消息（多轮上下文）
 * 5. 组装 system/user/assistant 消息数组发给大模型（含引用溯源指令）
 * 6. 保存助手回复，记录用量
 */
@Slf4j
@Service
public class ChatService {

    /** 从配置文件读取召回数量（默认 5） */
    @Value("${rag.top-k}")
    private int topK;

    /** 检索候选数量 */
    @Value("${rag.candidate-k:10}")
    private int candidateK = 10;

    /** 是否在正式问答前调用大模型改写检索问题，生产环境默认关闭以减少一次模型请求。 */
    @Value("${rag.query-rewrite-enabled:false}")
    private boolean queryRewriteEnabled = false;

    /** 是否调用大模型压缩检索上下文，生产环境默认关闭以减少一次模型请求。 */
    @Value("${rag.context-compression-enabled:false}")
    private boolean contextCompressionEnabled = false;

    /**
     * 多轮检索改写开关：追问（「它的上限是多少」）单靠当前这句话检索必然跑偏，
     * 打开后会在检测到追问时结合历史把问题补全成独立查询再检索。
     */
    @Value("${rag.multi-turn-query-enabled:true}")
    private boolean multiTurnQueryEnabled = true;

    /** 多轮检索改写时携带的历史轮数（一问一答算一轮） */
    @Value("${rag.multi-turn-history-turns:3}")
    private int multiTurnHistoryTurns = 3;

    /** 多轮对话：最多加载的历史消息条数 */
    private static final int MAX_HISTORY_MESSAGES = 10;

    /** 多轮改写时每条历史消息截断长度，避免把整段长回答塞进改写请求 */
    private static final int HISTORY_MESSAGE_MAX_CHARS = 200;

    /** 多轮改写时历史文本的总长度上限 */
    private static final int HISTORY_TEXT_MAX_CHARS = 1200;

    /** 追问特征词：出现这些词且句子很短时，判定为依赖上下文的追问 */
    private static final List<String> FOLLOW_UP_MARKERS = List.of(
            "它", "她", "这个", "那个", "这些", "那些", "该", "此", "上述", "上面", "刚才", "之前",
            "还有", "另外", "那么", "同样", "继续", "再来", "详细", "展开", "呢", "吗");

    /** 追问开头词：以这些词开头的短问句同样视为追问 */
    private static final List<String> FOLLOW_UP_PREFIXES = List.of(
            "那", "那么", "还有", "另外", "以及", "所以", "但是", "不过", "如果", "再", "接着");

    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    private final ChunkMapper chunkMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ModelProviderService modelProviderService;
    private final Reranker reranker;
    private final KeywordSearchService keywordSearchService;
    private final UsageService usageService;
    private final PromptGuard promptGuard;
    private final QueryRewriter queryRewriter;
    private final RetrievalCache retrievalCache;
    private final ContextCompressor contextCompressor;
    private final Executor chatStreamExecutor;
    /** 回答质量事实写入器，聊天主流程不可用时只记录日志。 */
    @org.springframework.beans.factory.annotation.Autowired
    private AnswerQualityMapper answerQualityMapper;
    /** 反馈读取器：历史消息附带点赞状态用于回显 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.rag.mapper.FeedbackMapper feedbackMapper;
    /** 追问建议生成器：回答完成后生成继续追问的问题 */
    @org.springframework.beans.factory.annotation.Autowired
    private FollowupSuggester followupSuggester;
    /** 检索候选选择器：无状态，由构造器手动创建，避免与注入实例并存 */
    private RetrievalSelector retrievalSelector;
    /** 知识库级检索策略读取器 */
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    /** 口径冲突读取器：检索命中冲突片段时给引用打标记；表未建或未注入时自动跳过 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rag.kb.KbConflictMapper kbConflictMapper;
    /** 可信回答校验器：无状态，由构造器手动创建，避免与注入实例并存 */
    private TrustedAnswerGuard trustedAnswerGuard;
    /** SSE 结构化事件序列化器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统提示词：要求模型基于知识库上下文回答并标注来源 */
    private static final String SYSTEM_PROMPT = "你是一个智能 AI 助手。\n\n"
            + "如果提供了知识库上下文，请优先基于上下文回答问题。\n"
            + "回答时请在相关语句后用 [来源: 文件名, 第N段] 标注引用来源。\n"
            + "如果没有提供知识库上下文，请直接使用大模型自身知识回答。\n"
            + "回答要简洁、准确、有帮助。";

    /** 匹配模型回答中的内部来源标记，返回给用户前会移除。 */
    private static final Pattern SOURCE_MARKER_PATTERN = Pattern.compile("\\[来源:\\s*[^\\]]+\\]");

    /** 构造注入所有依赖的组件 */
    public ChatService(EmbeddingService embeddingService, QdrantService qdrantService,
                       ChunkMapper chunkMapper, ChatMessageMapper chatMessageMapper,
                       ModelProviderService modelProviderService,
                       Reranker reranker, KeywordSearchService keywordSearchService,
                       UsageService usageService,
                       PromptGuard promptGuard, QueryRewriter queryRewriter,
                       RetrievalCache retrievalCache, ContextCompressor contextCompressor,
                       @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
        this.chunkMapper = chunkMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.modelProviderService = modelProviderService;
        this.reranker = reranker;
        this.keywordSearchService = keywordSearchService;
        this.usageService = usageService;
        this.promptGuard = promptGuard;
        this.queryRewriter = queryRewriter;
        this.retrievalCache = retrievalCache;
        this.contextCompressor = contextCompressor;
        this.chatStreamExecutor = chatStreamExecutor;
        this.retrievalSelector = new RetrievalSelector();
        this.trustedAnswerGuard = new TrustedAnswerGuard();
    }

    // ==================== 同步聊天 ====================

    /**
     * 同步聊天接口
     * 完整流程：安全检查 → 保存用户消息 → 检索知识库 → 构建多轮消息 → 调用大模型 → 保存回复 → 记录用量
     *
     * @param userId    当前用户ID
     * @param sessionId 会话ID
     * @param message   用户发送的消息内容
     * @param kbId      知识库ID（可选，为空则不检索知识库）
     * @return 大模型生成的回复文本
     */
    public String chat(Long userId, Long sessionId, String message, Long kbId) {
        // 安全检查：拦截 Prompt 注入攻击
        promptGuard.check(message);
        // 获取当前激活的大模型供应商配置
        ModelProvider provider = modelProviderService.getActive();
        // 保存用户消息到数据库
        saveMessage(userId, sessionId, "user", message);

        // 第1步：加载本会话历史（含刚保存的当前问题），供多轮检索改写判断是否追问
        List<com.rag.entity.ChatMessage> history = loadSessionHistory(userId, sessionId);
        // 第2步：混合检索 + 重排序，构建带来源信息的知识库上下文
        KnowledgeContext context = buildContextWithSources(message, kbId, history);
        //知识库模式没有证据时直接拒答，不调用大模型
        if (kbId != null && !context.hasContext()) {
            String refusal = trustedAnswerGuard.validate("", context).getContent();
            Long answerMessageId = saveMessage(userId, sessionId, "assistant", refusal);
            // 调用质量事实写入方法，记录无证据拒答
            recordAnswerQuality(answerMessageId, sessionId, kbId, message, 0, 0,
                    false, true, "NO_EVIDENCE");
            recordUsage(sessionId, provider, System.currentTimeMillis(), "SUCCESS", 0, 0);
            return refusal;
        }
        // 组装 system + 历史消息 + 当前问题的 LangChain4j 消息列表
        List<dev.langchain4j.data.message.ChatMessage> chatMessages =
                buildChatMessages(message, context, history);

        long startTime = System.currentTimeMillis();
        try {
            // 使用 LangChain4j 同步调用大模型，等待完整回复
            LlmResult llmResult = callLlmSync(chatMessages, provider);
            String answer = llmResult.content;
            if (kbId != null) {
                //知识库模式只保存经过来源校验的回答
                TrustedAnswerResult trusted = trustedAnswerGuard.validate(answer, context);
                // 先使用带内部来源标记的文本计算证据覆盖率，再清理标记后返回给用户
                Map<String, Object> metrics = AnswerEvidenceMetrics.calculate(
                        trusted.getContent(), trusted.isGrounded(), trusted.getCitations());
                // 移除仅供内部校验的来源标记，引用仍通过下方参考资料区域展示
                answer = removeSourceMarkers(trusted.getContent());
                Long answerMessageId = saveMessage(userId, sessionId, "assistant", answer);
                // 调用质量事实写入方法，记录同步回答指标
                recordAnswerQuality(answerMessageId, sessionId, kbId, message,
                        ((Number) metrics.get("confidence")).intValue(),
                        ((Number) metrics.get("evidenceCoverage")).intValue(),
                        trusted.isGrounded(), !trusted.isGrounded(), trusted.getReason());
            } else {
                // 普通聊天保持原有持久化逻辑
                saveMessage(userId, sessionId, "assistant", answer);
            }
            // 记录本次调用的 Token 用量和耗时
            recordUsage(sessionId, provider, startTime, "SUCCESS",
                    llmResult.promptTokens, llmResult.completionTokens);
            return answer;
        } catch (Exception e) {
            // 调用失败也记录用量（状态为 FAILED）
            recordUsage(sessionId, provider, startTime, "FAILED", 0, 0);
            throw e;
        }
    }

    // ==================== 流式聊天 ====================

    /**
     * 流式聊天接口
     * 保存用户消息后立即返回 SseEmitter，后台线程执行检索和流式推送
     * 前端通过 SSE 逐字接收大模型回复，实现打字机效果
     *
     * @param userId    当前用户ID
     * @param sessionId 会话ID
     * @param message   用户发送的消息内容
     * @param kbId      知识库ID（可选）
     * @return SSE 推送对象，前端通过此对象接收流式数据
     */
    public SseEmitter chatStream(Long userId, Long sessionId, String message, Long kbId) {
        // 安全检查：拦截 Prompt 注入攻击
        promptGuard.check(message);
        // 获取当前激活的大模型供应商配置
        ModelProvider provider = modelProviderService.getActive();
        // 保存用户消息到数据库
        saveMessage(userId, sessionId, "user", message);
        // 创建 SSE 推送对象（超时设为 0 表示不超时）
        SseEmitter emitter = new SseEmitter(0L);
        // 把检索和大模型调用放到后台线程执行，不阻塞主线程
        chatStreamExecutor.execute(() ->
                prepareAndStreamResponse(userId, sessionId, message, kbId, emitter, provider));
        // 立即返回 SSE 对象给控制器
        return emitter;
    }

    /**
     * 准备上下文并开始流式推送（在后台线程中执行）
     * 流程：检索知识库 → 构建消息 → 流式调用大模型 → 推送 token 到前端
     *
     * @param userId    当前用户ID
     * @param sessionId 会话ID
     * @param message   用户消息
     * @param kbId      知识库ID
     * @param emitter   SSE 推送对象
     * @param provider  大模型供应商配置
     */
    private void prepareAndStreamResponse(Long userId, Long sessionId, String message, Long kbId,
                                          SseEmitter emitter, ModelProvider provider) {
        long startTime = System.currentTimeMillis();
        try {
            // 通知前端正在检索知识库
            safeSend(emitter, SseEmitter.event().name("status").data("正在检索知识库并生成内容..."));
            // 第1步：加载本会话历史（含刚保存的当前问题），供多轮检索改写判断是否追问
            List<com.rag.entity.ChatMessage> history = loadSessionHistory(userId, sessionId);
            // 第2步：混合检索 + 重排序，构建知识库上下文
            KnowledgeContext context = buildContextWithSources(message, kbId, history);
            if (kbId != null) {
                //先发送引用元数据，前端可以在回答下方展示参考资料
                safeSendJson(emitter, "citation", context.getCitations());
                if (!context.hasContext()) {
                    String refusal = trustedAnswerGuard.validate("", context).getContent();
                    Long answerMessageId = saveMessage(userId, sessionId, "assistant", refusal);
                    // 调用质量事实写入方法，记录流式无证据拒答
                    recordAnswerQuality(answerMessageId, sessionId, kbId, message, 0, 0,
                            false, true, "NO_EVIDENCE");
                    safeSend(emitter, SseEmitter.event().name("message").data(refusal));
                    // 计算无证据拒答的回答指标并保持指标归零
                    Map<String, Object> noEvidenceMeta = new LinkedHashMap<>(AnswerEvidenceMetrics.calculate(refusal, false, List.of()));
                    noEvidenceMeta.put("grounded", false);
                    noEvidenceMeta.put("reason", "NO_EVIDENCE");
                    safeSendJson(emitter, "answer-meta", noEvidenceMeta);
                    safeSend(emitter, SseEmitter.event().name("done").data("[DONE]"));
                    safeComplete(emitter);
                    recordUsage(sessionId, provider, startTime, "SUCCESS", 0, 0);
                    return;
                }
            }
            // 组装 LangChain4j 消息列表
            List<dev.langchain4j.data.message.ChatMessage> chatMessages =
                    buildChatMessages(message, context, history);
            // 通知前端正在生成
            safeSend(emitter, SseEmitter.event().name("status").data("正在生成..."));
            // 使用 LangChain4j 流式调用大模型，逐段推送到前端
            LlmResult llmResult = streamLlmResponse(userId, sessionId, message, kbId,
                    chatMessages, emitter, provider, context);
            // 记录 Token 用量
            recordUsage(sessionId, provider, startTime, "SUCCESS",
                    llmResult.promptTokens, llmResult.completionTokens);
        } catch (Exception e) {
            log.error("Failed to prepare stream response", e);
            recordUsage(sessionId, provider, startTime, "FAILED", 0, 0);
            completeEmitterWithError(emitter, e);
        }
    }

    /**
     * 使用 LangChain4j 流式调用大模型
     *
     * @param userId       当前用户ID
     * @param sessionId    会话ID
     * @param chatMessages 组装好的 LangChain4j 消息列表
     * @param emitter   SSE 推送对象
     * @param provider  大模型供应商配置
     * @return LlmResult 包含完整回复内容和 Token 用量
     */
    private LlmResult streamLlmResponse(Long userId, Long sessionId, String question, Long kbId,
                                        List<dev.langchain4j.data.message.ChatMessage> chatMessages,
                                        SseEmitter emitter, ModelProvider provider, KnowledgeContext context) {
        // 第1步：创建完整回复缓存
        StringBuilder fullResponse = new StringBuilder();
        // 第2步：创建 Token 用量缓存
        int[] tokenUsage = {0, 0};
        // 第3步：创建回调结束等待器
        CountDownLatch streamDoneLatch = new CountDownLatch(1);
        // 第4步：创建回调异常缓存
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        try {
            // 第5步：根据数据库供应商配置动态创建 LangChain4j 流式模型
            OpenAiStreamingChatModel chatModel = createLangChain4jStreamingChatModel(provider);
            // 第6步：通知前端连接已经建立
            safeSend(emitter, SseEmitter.event().name("open").data("connected"));
            // 第7步：订阅 LangChain4j 流式响应并逐段推送给前端
            chatModel.generate(chatMessages, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    // 第1步：过滤空 token
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    // 第2步：累积完整回复，知识库模式在校验前不推送正文
                    fullResponse.append(token);
                    if (!context.hasContext()) {
                        safeSend(emitter, SseEmitter.event().name("message").data(token));
                    }
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    // 第1步：读取最终 Token 用量
                    parseUsage(response, tokenUsage);
                    String answer = fullResponse.toString();
                    if (context.hasContext()) {
                        //知识库模式在完整回答生成后校验来源，再一次性发送可信回答
                        TrustedAnswerResult trusted = trustedAnswerGuard.validate(answer, context);
                        // 先使用带内部来源标记的文本计算证据覆盖率，再清理标记后返回给用户
                        String validatedAnswer = trusted.getContent();
                        // 计算可信回答的置信度、证据覆盖率和引用数量
                        Map<String, Object> answerMeta = new LinkedHashMap<>(AnswerEvidenceMetrics.calculate(
                                validatedAnswer, trusted.isGrounded(), trusted.getCitations()));
                        answerMeta.put("grounded", trusted.isGrounded());
                        answerMeta.put("reason", trusted.getReason());
                        // 移除仅供内部校验的来源标记，引用仍通过下方参考资料区域展示
                        answer = removeSourceMarkers(validatedAnswer);
                        // 保存不带来源标记的回答
                        Long answerMessageId = saveMessage(userId, sessionId, "assistant", answer);
                        // 推送不带来源标记的回答正文
                        safeSend(emitter, SseEmitter.event().name("message").data(answer));
                        // 推送回答元数据
                        safeSendJson(emitter, "answer-meta", answerMeta);
                        // 调用质量事实写入方法，记录流式回答指标
                        recordAnswerQuality(answerMessageId, sessionId, kbId, question,
                                ((Number) answerMeta.get("confidence")).intValue(),
                                ((Number) answerMeta.get("evidenceCoverage")).intValue(),
                                trusted.isGrounded(), !trusted.isGrounded(), trusted.getReason());
                    } else if (!fullResponse.isEmpty()) {
                        //普通聊天保持原有保存逻辑
                        saveMessage(userId, sessionId, "assistant", answer);
                    }
                    // 第3步：生成追问建议并在 done 前下发；失败静默降级为无建议
                    try {
                        java.util.List<String> followups = followupSuggester.suggest(question, answer);
                        if (!followups.isEmpty()) {
                            safeSendJson(emitter, "followups", followups);
                        }
                    } catch (Exception e) {
                        log.warn("追问建议下发失败: {}", e.getMessage());
                    }
                    // 第4步：通知前端流式输出完成
                    safeSend(emitter, SseEmitter.event().name("done").data("[DONE]"));
                    safeComplete(emitter);
                    // 第4步：通知外层流式调用已经结束
                    streamDoneLatch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    // 第1步：记录 LangChain4j 回调异常
                    log.error("LangChain4j streaming callback error, provider={}, model={}", provider.getName(), provider.getModel(), error);
                    // 第2步：如果已经生成了部分内容，就保存部分内容
                    if (!fullResponse.isEmpty() && !context.hasContext()) {
                        saveMessage(userId, sessionId, "assistant", fullResponse.toString());
                    }
                    // 第3步：向前端推送错误并关闭连接
                    Exception exception = error instanceof Exception e ? e : new RuntimeException(error);
                    streamError.set(exception);
                    completeEmitterWithError(emitter, exception);
                    // 第4步：通知外层流式调用已经结束
                    streamDoneLatch.countDown();
                }
            });
            // 第8步：等待 LangChain4j 回调结束，确保用量记录发生在最终结果之后
            streamDoneLatch.await();
            // 第9步：如果回调中发生异常，就抛给外层记录失败状态
            if (streamError.get() != null) {
                throw new RuntimeException(streamError.get());
            }
        } catch (Exception e) {
            // 第10步：流式调用失败时记录日志
            log.error("LangChain4j streaming error, provider={}, model={}", provider.getName(), provider.getModel(), e);
            // 第11步：如果已经生成了部分内容，就先保存部分内容
            if (!fullResponse.isEmpty() && !context.hasContext()) {
                saveMessage(userId, sessionId, "assistant", fullResponse.toString());
            }
            // 第12步：把异常继续抛给外层统一处理
            throw new RuntimeException("Failed to call LLM API by LangChain4j: " + buildUserErrorMessage(e), e);
        }
        // 第13步：返回完整回复和用量
        return new LlmResult(fullResponse.toString(), tokenUsage[0], tokenUsage[1]);
    }

    /**
     * 使用 LangChain4j 同步调用大模型
     *
     * @param chatMessages 组装好的 LangChain4j 消息列表
     * @param provider     大模型供应商配置
     * @return LlmResult 包含回复内容和 Token 用量
     */
    private LlmResult callLlmSync(List<dev.langchain4j.data.message.ChatMessage> chatMessages, ModelProvider provider) {
        try {
            // 第1步：根据数据库供应商配置动态创建 LangChain4j 模型
            OpenAiChatModel chatModel = createLangChain4jChatModel(provider);
            // 第2步：同步调用模型
            Response<AiMessage> response = chatModel.generate(chatMessages);
            // 第3步：提取回复正文
            String content = extractResponseText(response);
            // 第4步：提取 Token 用量
            int[] tokenUsage = {0, 0};
            parseUsage(response, tokenUsage);
            // 第5步：返回调用结果
            return new LlmResult(content, tokenUsage[0], tokenUsage[1]);
        } catch (Exception e) {
            // 第6步：调用失败时记录日志
            log.error("LangChain4j call failed, provider={}, model={}", provider.getName(), provider.getModel(), e);
            // 第7步：抛出统一错误信息
            throw new RuntimeException("Failed to call LLM API by LangChain4j: " + buildUserErrorMessage(e), e);
        }
    }

    // ==================== 多轮对话上下文 ====================

    /**
     * 构建发送给大模型的完整消息数组
     * 包含三部分：system 提示词（含知识库上下文）、历史对话消息、当前用户问题
     *
     * @param userId       当前用户ID
     * @param sessionId    会话ID（用于加载历史消息）
     * @param message      当前用户问题
     * @param context 知识库检索结果（含来源标注）
     * @return 消息数组，按 system → history → user 排列
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildChatMessages(
            String message, KnowledgeContext context, List<com.rag.entity.ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // 第1步：构建系统提示词
        String systemContent = SYSTEM_PROMPT;
        if (context.hasContext()) {
            // 第2步：按配置决定是否调用额外模型压缩上下文，默认直接使用检索结果降低等待时间
            String compressedContext = contextCompressionEnabled
                    ? contextCompressor.compress(message, context.getContextWithSources())
                    : context.getContextWithSources();
            systemContent += "\n\n以下是知识库中的相关内容：\n\n" + compressedContext
                    + "\n\n只能依据以上内容回答，并且必须在相关语句后使用准确的 [来源: 文件名, 第N段] 引用。没有依据时不要补充外部知识。";
        }
        // 第3步：把系统提示词加入 LangChain4j 消息列表
        messages.add(SystemMessage.from(systemContent));

        // 第4步：直接使用调用方已加载的历史消息（与检索用的是同一份，避免重复查库）
        if (history != null && history.size() > 1) {
            // 第5步：去掉最后一条当前用户消息，避免重复发送
            List<com.rag.entity.ChatMessage> previousMessages = history.subList(0, history.size() - 1);
            // 第6步：只取最近若干条历史消息，避免上下文过长
            int start = Math.max(0, previousMessages.size() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < previousMessages.size(); i++) {
                com.rag.entity.ChatMessage msg = previousMessages.get(i);
                // 第7步：按角色转换为 LangChain4j 消息类型
                if ("assistant".equals(msg.getRole())) {
                    messages.add(AiMessage.from(msg.getContent()));
                } else {
                    messages.add(UserMessage.from(msg.getContent()));
                }
            }
        }

        // 第8步：加入当前用户消息
        messages.add(UserMessage.from(message));

        return messages;
    }

    // ==================== 混合检索 + 查询改写 + 缓存 + 引用溯源 ====================

    /**
     * 构建知识库上下文（带来源信息）
     * 完整流程：查询改写 → 缓存检查 → 向量检索 + 关键词检索 → 合并去重 → 重排序 → 返回结构化结果
     *
     * @param message 用户原始问题
     * @param kbId    知识库ID（为空时不检索，返回空结果）
     * @param history 本会话历史消息（含当前这一条），用于追问补全
     * @return ContextResult 包含纯文本上下文和带来源标注的上下文
     */
    private KnowledgeContext buildContextWithSources(String message, Long kbId,
                                                     List<com.rag.entity.ChatMessage> history) {
        if (kbId == null) {
            return KnowledgeContext.empty();
        }
        try {
            // 第1步：读取知识库策略并改写查询；单元测试或非 Spring 调用未注入 Mapper 时使用默认策略
            com.rag.entity.KnowledgeBase strategy = knowledgeBaseMapper == null ? null : knowledgeBaseMapper.findById(kbId);
            int effectiveTopK = strategy != null && strategy.getTopK() != null ? strategy.getTopK() : topK;
            float threshold = strategy != null && strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold().floatValue() : 0.25f;
            float vectorWeight = strategy != null && strategy.getVectorWeight() != null ? strategy.getVectorWeight().floatValue() : 0.6f;
            float keywordWeight = strategy != null && strategy.getKeywordWeight() != null ? strategy.getKeywordWeight().floatValue() : 0.4f;
            // 第2步：结合对话历史解析本轮真正用于检索的问题（追问会被补全成独立问题）
            String rawQuery = message == null ? "" : message.trim();
            String searchQuery = resolveRetrievalQuery(rawQuery, history);
            // 第3步：读取本库已登记的口径冲突，命中的片段会在上下文与引用上带标记
            Map<Long, com.rag.kb.KbConflict> chunkConflicts = loadChunkConflicts(kbId);
            // 第4步：用最终检索问题查缓存，命中时不再调用向量与关键词检索
            List<Long> cachedIds = retrievalCache.get(kbId, searchQuery);
            if (cachedIds != null && !cachedIds.isEmpty()) {
                // 根据缓存的文本块编号读取当前知识库中的有效文本块
                List<KbChunk> cachedChunks = findKbChunks(cachedIds, kbId);
                if (!cachedChunks.isEmpty()) {
                    // 返回缓存上下文，跳过后续模型和检索请求
                    return buildKnowledgeContext(cachedChunks, searchQuery, Collections.emptyMap(),
                            Collections.emptySet(), chunkConflicts);
                }
            }

            // 第5步：关键词检索与向量化互不依赖，先把关键词检索提交出去，再在本地算向量
            int effectiveCandidateK = Math.max(candidateK, effectiveTopK * 2);
            // 固定最终检索词，供并行任务安全读取
            String retrievalQuery = searchQuery;
            // 关键词检索只需要文本，先交出去，避免它的数据库查询与 embedding 的网络往返串行等待
            CompletableFuture<List<KbChunk>> keywordFuture = CompletableFuture.supplyAsync(
                    () -> keywordSearchService.search(retrievalQuery, kbId, effectiveCandidateK));
            // 本线程生成查询向量（一次 HTTP 调用），这段时间关键词检索已经在跑
            float[] queryEmbedding = embeddingService.embed(searchQuery);
            // 向量检索同样并行执行
            CompletableFuture<List<SearchResult>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> qdrantService.searchWithScore(queryEmbedding, effectiveCandidateK, kbId));
            List<SearchResult> vectorResults;
            List<KbChunk> keywordChunks;
            try {
                // 等待向量检索任务完成并读取结果
                vectorResults = vectorFuture.get();
                // 等待关键词检索任务完成并读取结果
                keywordChunks = keywordFuture.get();
            } catch (InterruptedException e) {
                // 恢复线程中断状态，避免吞掉上层取消信号
                Thread.currentThread().interrupt();
                throw new RuntimeException("Knowledge retrieval interrupted", e);
            } catch (ExecutionException e) {
                // 保留向量维度不匹配等业务异常的原始类型
                if (e.getCause() instanceof BusinessException businessException) {
                    throw businessException;
                }
                throw new RuntimeException(e.getCause());
            }
            List<Long> vectorIds = vectorResults.stream().map(r -> r.id).collect(Collectors.toList());
            Set<Long> vectorIdSet = new HashSet<>(vectorIds);
            Map<Long, Float> vectorScoreById = vectorResults.stream()
                    .collect(Collectors.toMap(result -> result.id, result -> result.score, Math::max));

            // 第6步：整理关键词检索结果并合并去重
            Set<Long> keywordIds = keywordChunks.stream().map(KbChunk::getId).collect(Collectors.toSet());
            // 全文索引的原始相关度：让关键词分数从"命中/未命中"细化为真实强弱
            Map<Long, Double> keywordRelevance = new HashMap<>();
            for (KbChunk chunk : keywordChunks) {
                keywordRelevance.merge(chunk.getId(), chunk.getRelevance() == null ? 0d : chunk.getRelevance(), Math::max);
            }
            double maxKeywordRelevance = keywordRelevance.values().stream().mapToDouble(Double::doubleValue).max().orElse(0d);

            // 第7步：合并去重（向量结果 + 关键词结果，去掉重复的 chunk）
            Set<Long> allIds = new LinkedHashSet<>(vectorIds);
            List<KbChunk> vectorChunks = findKbChunks(vectorIds, kbId);
            for (KbChunk chunk : keywordChunks) {
                if (allIds.add(chunk.getId())) {
                    // 关键词检索命中但向量检索没命中的，补充进来
                    vectorChunks.add(chunk);
                    vectorScoreById.put(chunk.getId(), 0.3f); // 给关键词命中的一个基础分数
                }
            }

            // 兼容旧数据：如果带 kb_id 过滤没命中，去掉过滤再搜一次
            if (vectorChunks.isEmpty()) {
                List<Long> legacyIds = qdrantService.search(queryEmbedding, effectiveTopK);
                vectorChunks = findKbChunks(legacyIds, kbId);
            }
            if (vectorChunks.isEmpty()) {
                return KnowledgeContext.empty();
            }

            // 第8步：融合分数、过滤低分结果并去除高度重复片段
            List<RetrievalCandidate> candidates = new ArrayList<>();
            for (KbChunk chunk : vectorChunks) {
                boolean keywordMatched = keywordIds.contains(chunk.getId());
                boolean vectorMatched = vectorIdSet.contains(chunk.getId());
                RetrievalCandidate candidate = new RetrievalCandidate(chunk);
                candidate.setVectorScore(vectorScoreById.getOrDefault(chunk.getId(), 0.3f));
                candidate.setKeywordScore(RetrievalCandidate.normalizeKeywordScore(
                        keywordMatched, keywordRelevance.get(chunk.getId()), maxKeywordRelevance));
                candidate.setMatchType(vectorMatched && keywordMatched ? "HYBRID"
                        : keywordMatched ? "KEYWORD" : "VECTOR");
                candidates.add(candidate);
            }
            // 第8步：冲突配对补齐——两版口径必须并排可见，配对的另一段没被召回时从库里补回来
            Set<Long> conflictChunkIds = amplifyConflictCandidates(candidates, chunkConflicts, kbId);
            List<RetrievalCandidate> selected = retrievalSelector.select(candidates, effectiveTopK,
                    threshold, vectorWeight, keywordWeight, 0.85f, conflictChunkIds);
            if (selected.isEmpty()) {
                return KnowledgeContext.empty();
            }

            // 第9步：写入缓存（下次相同问题直接命中）
            List<Long> selectedIds = selected.stream().map(candidate -> candidate.getChunk().getId()).collect(Collectors.toList());
            retrievalCache.put(kbId, searchQuery, selectedIds);

            return buildKnowledgeContext(selected, searchQuery, chunkConflicts);
        } catch (BusinessException e) {
            //向量维度不匹配等业务异常原样抛出，保留可行动的错误提示
            throw e;
        } catch (Exception e) {
            log.error("Failed to build knowledge context", e);
            throw new RuntimeException("Knowledge retrieval failed", e);
        }
    }

    // ==================== 多轮检索改写 ====================

    /** 加载会话历史（含当前这一条），供多轮检索改写与消息组装共用 */
    private List<com.rag.entity.ChatMessage> loadSessionHistory(Long userId, Long sessionId) {
        try {
            List<com.rag.entity.ChatMessage> history =
                    chatMessageMapper.findByUserIdAndSessionId(userId, sessionId);
            return history == null ? Collections.emptyList() : history;
        } catch (Exception e) {
            log.warn("加载会话历史失败，本轮按单轮问题检索: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析本轮真正用于检索的问题
     * 第1步：判断这句话是否依赖上下文（短句 + 含指代/追问词）；不是就直接用原句，不做额外模型调用
     * 第2步：是追问且存在历史时，调用模型结合历史补全成独立问题
     * 第3步：模型失败或返回空时退回原句；未开多轮开关时保留原有的单轮改写行为
     */
    private String resolveRetrievalQuery(String rawQuery, List<com.rag.entity.ChatMessage> history) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return rawQuery == null ? "" : rawQuery;
        }
        boolean hasHistory = history != null && history.size() > 1;
        // 第1步：只有追问才补全，普通独立问题保持原样
        if (multiTurnQueryEnabled && hasHistory && looksLikeFollowUp(rawQuery)) {
            String historyText = formatHistory(history, multiTurnHistoryTurns);
            String rewritten = queryRewriter.rewriteWithHistory(rawQuery, historyText);
            if (rewritten != null && !rewritten.isBlank()) {
                log.debug("多轮检索改写: '{}' -> '{}'", rawQuery, rewritten);
                return rewritten.trim();
            }
            return rawQuery;
        }
        // 第2步：兼容原有的单轮改写开关
        if (queryRewriteEnabled) {
            String rewritten = queryRewriter.rewrite(rawQuery);
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        }
        return rawQuery;
    }

    /**
     * 判断一句话是否依赖上下文（追问）
     * 判据：句子不超过 30 字，且含指代词/承接词，或以「那/还有/另外」等词开头
     */
    static boolean looksLikeFollowUp(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String text = query.trim();
        // 长句通常自带完整语义，不做改写，避免额外模型开销
        if (text.length() > 30) {
            return false;
        }
        for (String prefix : FOLLOW_UP_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        // 句末语气词（呢/吗）且不超过 15 字，视为追问
        if (text.length() <= 15 && (text.endsWith("呢") || text.endsWith("吗"))) {
            return true;
        }
        for (String marker : FOLLOW_UP_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把历史消息格式化成模型可读的对话文本
     * 只取最近 turns 轮、跳过当前这一条用户消息，并对每条内容做长度截断
     */
    static String formatHistory(List<com.rag.entity.ChatMessage> history, int turns) {
        if (history == null || history.size() <= 1) {
            return "";
        }
        // 第1步：去掉最后一条（当前用户问题），只保留此前的对话
        List<com.rag.entity.ChatMessage> previous = history.subList(0, history.size() - 1);
        // 第2步：按轮数换算成消息条数（一问一答算两条）
        int maxMessages = Math.max(1, turns) * 2;
        int start = Math.max(0, previous.size() - maxMessages);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < previous.size(); i++) {
            com.rag.entity.ChatMessage msg = previous.get(i);
            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            // 第3步：单条过长时截断，避免改写请求本身超长
            if (content.length() > HISTORY_MESSAGE_MAX_CHARS) {
                content = content.substring(0, HISTORY_MESSAGE_MAX_CHARS) + "…";
            }
            // 第4步：总量超限就停止追加，历史只需要够模型消解指代
            if (builder.length() + content.length() > HISTORY_TEXT_MAX_CHARS) {
                break;
            }
            String role = "assistant".equals(msg.getRole()) ? "助手" : "用户";
            builder.append(role).append("：").append(content).append('\n');
        }
        return builder.toString().trim();
    }

    /**
     * 从 chunk 列表构建带来源信息的上下文结果
     * 生成两个版本：纯文本版（用于缓存）和带来源标注版（用于 prompt）
     *
     * @param chunks 重排序后的 chunk 列表
     * @return ContextResult 包含两种格式的上下文文本
     */
    private KnowledgeContext buildKnowledgeContext(List<KbChunk> chunks, String rewrittenQuery,
                                                   Map<Long, Float> vectorScores, Set<Long> keywordIds,
                                                   Map<Long, com.rag.kb.KbConflict> conflicts) {
        StringBuilder contextText = new StringBuilder();
        StringBuilder contextWithSources = new StringBuilder();
        List<KnowledgeCitation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunk chunk = chunks.get(i);
            if (i > 0) {
                contextText.append("\n\n");
                contextWithSources.append("\n\n");
            }
            // 纯文本版本
            contextText.append(chunk.getContent());
            // 带来源标注的版本：[来源: 文件名, 第N段]
            String source = chunk.getSourceInfo() != null ? chunk.getSourceInfo() : "文档";
            contextWithSources.append("[来源: ").append(source).append("]\n").append(chunk.getContent());
            // 口径冲突提示：命中已登记的冲突片段时，要求模型说明两种说法的差异，而不是挑一个当作唯一答案
            String conflictNote = conflicts == null ? null : buildConflictNote(conflicts.get(chunk.getId()), chunk.getId());
            if (conflictNote != null) {
                contextWithSources.append("\n[注意: 本段与其他文档存在口径冲突——")
                        .append(conflictNote)
                        .append("。若引用本段，请明确说明两种口径的差异，不要只给出其中一个数值。]");
            }
            KnowledgeCitation citation = new KnowledgeCitation();
            citation.setFileId(chunk.getFileId());
            citation.setFileName(chunk.getFileName() == null ? "文档" : chunk.getFileName());
            citation.setChunkId(chunk.getId());
            citation.setChunkIndex(chunk.getChunkIndex());
            citation.setSource(source);
            citation.setContentPreview(buildContentPreview(chunk.getContent()));
            citation.setVectorScore(vectorScores.getOrDefault(chunk.getId(), 0f));
            citation.setKeywordScore(keywordIds.contains(chunk.getId()) ? 1f : 0f);
            citation.setMatchType(keywordIds.contains(chunk.getId()) ? "KEYWORD" : "VECTOR");
            if (conflictNote != null) {
                citation.setConflict(Boolean.TRUE);
                citation.setConflictNote(conflictNote);
            }
            citations.add(citation);
        }
        return new KnowledgeContext(contextText.toString(), contextWithSources.toString(), citations, rewrittenQuery);
    }

    /**
     * 从带分数的检索候选构建知识库上下文
     */
    private KnowledgeContext buildKnowledgeContext(List<RetrievalCandidate> candidates, String rewrittenQuery,
                                                   Map<Long, com.rag.kb.KbConflict> conflicts) {
        List<KbChunk> chunks = candidates.stream().map(RetrievalCandidate::getChunk).toList();
        Map<Long, Float> vectorScores = new HashMap<>();
        Set<Long> keywordIds = new HashSet<>();
        for (RetrievalCandidate candidate : candidates) {
            vectorScores.put(candidate.getChunk().getId(), candidate.getVectorScore());
            if (candidate.getKeywordScore() > 0) {
                keywordIds.add(candidate.getChunk().getId());
            }
        }
        KnowledgeContext context = buildKnowledgeContext(chunks, rewrittenQuery, vectorScores, keywordIds, conflicts);
        for (int index = 0; index < candidates.size() && index < context.getCitations().size(); index++) {
            KnowledgeCitation citation = context.getCitations().get(index);
            RetrievalCandidate candidate = candidates.get(index);
            citation.setKeywordScore(candidate.getKeywordScore());
            citation.setFinalScore(candidate.getFinalScore());
            citation.setMatchType(candidate.getMatchType());
        }
        return context;
    }

    /**
     * 生成引用内容预览
     */
    private String buildContentPreview(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r", " ").replace("\n", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
    }

    /**
     * 读取本知识库已登记的口径冲突，整理成「片段编号 → 冲突」映射。
     * 人工已忽略（DISMISSED）的冲突不参与，避免确认过的误报反复打扰；
     * 同一片段命中多条冲突时优先取已确认的，其次取相似度更高的。
     */
    private Map<Long, com.rag.kb.KbConflict> loadChunkConflicts(Long kbId) {
        if (kbConflictMapper == null || kbId == null) {
            return Collections.emptyMap();
        }
        try {
            List<com.rag.kb.KbConflict> conflicts = kbConflictMapper.findByKb(kbId, null);
            if (conflicts == null || conflicts.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<Long, com.rag.kb.KbConflict> byChunk = new HashMap<>();
            for (com.rag.kb.KbConflict conflict : conflicts) {
                if (conflict == null || "DISMISSED".equalsIgnoreCase(conflict.getConflictStatus())) {
                    continue;
                }
                mergeConflict(byChunk, conflict.getChunkAId(), conflict);
                mergeConflict(byChunk, conflict.getChunkBId(), conflict);
            }
            return byChunk;
        } catch (Exception e) {
            // 冲突标记属于增强信息，读取失败只降级，不能影响正常问答
            log.warn("读取口径冲突失败，本次回答不带冲突标记: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 冲突配对补齐：把已召回片段的对侧版本补进候选，并返回需要豁免去重的切片编号。
     * 为什么需要：同一制度的两版文档往往高度相似（实测相似度 0.88），去重逻辑会把后一条
     * 判为 DUPLICATE_CONTENT 直接丢掉，用户只能看到其中一版口径，而冲突恰恰要靠并排对比才成立。
     *
     * @param candidates     已构建的候选（会被追加补取的配对片段）
     * @param chunkConflicts 片段编号到冲突记录的映射
     * @param kbId           知识库ID，补取时用于归属校验
     * @return 需要豁免「低分过滤」与「重复内容过滤」的切片编号集合
     */
    private Set<Long> amplifyConflictCandidates(List<RetrievalCandidate> candidates,
                                                Map<Long, com.rag.kb.KbConflict> chunkConflicts,
                                                Long kbId) {
        if (chunkConflicts == null || chunkConflicts.isEmpty() || candidates.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> presentIds = candidates.stream()
                .map(candidate -> candidate.getChunk().getId())
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> conflictChunkIds = new HashSet<>();
        List<RetrievalCandidate> missing = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            Long chunkId = candidate.getChunk().getId();
            com.rag.kb.KbConflict conflict = chunkConflicts.get(chunkId);
            if (conflict == null || conflict.getChunkAId() == null || conflict.getChunkBId() == null) {
                continue;
            }
            Long partnerId = chunkId.equals(conflict.getChunkAId())
                    ? conflict.getChunkBId() : conflict.getChunkAId();
            // 两侧都要豁免：谁在前谁在后取决于分数，只有都保护才不会被去重干掉一个
            conflictChunkIds.add(chunkId);
            conflictChunkIds.add(partnerId);
            if (presentIds.contains(partnerId)) {
                continue;
            }
            List<KbChunk> fetched = findKbChunks(Collections.singletonList(partnerId), kbId);
            if (fetched.isEmpty()) {
                continue;
            }
            RetrievalCandidate extra = new RetrievalCandidate(fetched.get(0));
            // 沿用对侧的向量分数，让配对片段紧挨着它出现，而不是排到最后
            extra.setVectorScore(candidate.getVectorScore());
            extra.setKeywordScore(0f);
            extra.setMatchType("CONFLICT");
            missing.add(extra);
            presentIds.add(partnerId);
        }
        candidates.addAll(missing);
        if (!missing.isEmpty()) {
            log.info("口径冲突补齐：为 {} 个冲突片段补入 {} 个未召回的配对切片", conflictChunkIds.size(), missing.size());
        }
        return conflictChunkIds;
    }

    /** 同一片段命中多条冲突时保留优先级更高的一条 */
    private void mergeConflict(Map<Long, com.rag.kb.KbConflict> byChunk, Long chunkId,
                               com.rag.kb.KbConflict conflict) {        if (chunkId == null) {
            return;
        }
        com.rag.kb.KbConflict existing = byChunk.get(chunkId);
        if (existing == null || conflictPriority(conflict) > conflictPriority(existing)) {
            byChunk.put(chunkId, conflict);
        }
    }

    /** 冲突优先级：已确认的排在前，其次比相似度 */
    private int conflictPriority(com.rag.kb.KbConflict conflict) {
        int confirmed = "CONFIRMED".equalsIgnoreCase(conflict.getConflictStatus()) ? 1000 : 0;
        float similarity = conflict.getSimilarity() == null ? 0f : conflict.getSimilarity();
        return confirmed + Math.round(similarity * 100);
    }

    /**
     * 把一条冲突记录翻译成一句人话，prompt 与前端引用共用同一段文案。
     *
     * @param conflict 冲突记录
     * @param chunkId  当前片段编号，用于判断自己在冲突的哪一侧
     * @return 冲突说明；无冲突时返回 null
     */
    static String buildConflictNote(com.rag.kb.KbConflict conflict, Long chunkId) {
        if (conflict == null) {
            return null;
        }
        boolean currentIsA = conflict.getChunkAId() != null && conflict.getChunkAId().equals(chunkId);
        String otherFile = currentIsA ? conflict.getFileBName() : conflict.getFileAName();
        Integer otherIndex = currentIsA ? conflict.getChunkBIndex() : conflict.getChunkAIndex();
        String unit = conflict.getConflictUnit() == null ? "" : conflict.getConflictUnit();
        String valueA = conflict.getValueA() == null ? "?" : conflict.getValueA();
        String valueB = conflict.getValueB() == null ? "?" : conflict.getValueB();
        StringBuilder note = new StringBuilder();
        note.append("与《").append(otherFile == null ? "其他文档" : otherFile).append("》");
        if (otherIndex != null) {
            note.append("第").append(otherIndex + 1).append("段");
        }
        note.append("在「").append(unit).append("」上的取值不一致（")
                .append(valueA).append(" 对 ").append(valueB).append("）");
        return note.toString();
    }

    /**
     * 移除模型回答中的内部来源标记，避免把技术校验文本展示给用户。
     *
     * @param answer 已通过来源校验的回答文本
     * @return 不包含来源标记的回答文本
     */
    private String removeSourceMarkers(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }
        // 清理来源标记后保留模型原有的正文和 Markdown 结构
        return SOURCE_MARKER_PATTERN.matcher(answer).replaceAll("").trim();
    }

    /**
     * 根据 chunk ID 列表从 MySQL 查询对应的文本块
     * 通过 findByIdAndKbId 确保 chunk 属于指定的知识库（权限隔离）
     *
     * @param chunkIds chunk ID 列表（来自 Qdrant 检索结果）
     * @param kbId     知识库ID（用于权限校验）
     * @return 查询到的 chunk 列表（过滤掉不存在或不属于该知识库的）
     */
    private List<KbChunk> findKbChunks(List<Long> chunkIds, Long kbId) {
        return chunkIds.stream()
                .map(chunkId -> chunkMapper.findByIdAndKbId(chunkId, kbId))
                .filter(chunk -> chunk != null)
                .collect(Collectors.toList());
    }

    /**
     * 生产链路的检索结果（供评测台使用）
     * 直接复用 buildContextWithSources，保证评测口径与实际问答完全一致：
     * 同样的知识库策略（topK/阈值/权重）、同样的向量 + 关键词召回、同样的去重规则。
     *
     * @param question 评测问题
     * @param kbId     知识库ID
     * @return 命中的引用列表（按得分排序），无命中时返回空列表
     */
    public List<KnowledgeCitation> retrieveCitations(String question, Long kbId) {
        if (kbId == null || question == null || question.isBlank()) {
            return Collections.emptyList();
        }
        KnowledgeContext context = buildContextWithSources(question, kbId, Collections.emptyList());
        return context.getCitations() == null ? Collections.emptyList() : context.getCitations();
    }

    /**
     * 召回测试接口（供 ChatController 调用）
     * 输入问题和知识库ID，返回召回的 chunk 列表，用于调试检索质量
     * 每个 chunk 包含排名、chunkID、来源信息和内容
     *
     * @param message 用户输入的测试问题
     * @param kbId    知识库ID
     * @return 召回结果列表，每项包含 rank、chunkId、source、content
     */
    public List<Map<String, Object>> recallTest(String message, Long kbId) {
        if (kbId == null) {
            return Collections.emptyList();
        }
        try {
            // 查询改写
            String rewrittenQuery = queryRewriter.rewrite(message);
            String searchQuery = rewrittenQuery.isEmpty() ? message : rewrittenQuery;

            // 向量检索
            float[] queryEmbedding = embeddingService.embed(searchQuery);
            List<SearchResult> vectorResults = qdrantService.searchWithScore(queryEmbedding, candidateK, kbId);
            List<Long> vectorIds = vectorResults.stream().map(r -> r.id).collect(Collectors.toList());
            List<Float> vectorScores = vectorResults.stream().map(r -> r.score).collect(Collectors.toList());
            Map<Long, Float> vectorScoreByChunkId = vectorResults.stream()
                    .collect(Collectors.toMap(result -> result.id, result -> result.score, Math::max));

            // 关键词检索
            List<KbChunk> keywordChunks = keywordSearchService.search(searchQuery, kbId, topK);
            Set<Long> keywordChunkIds = keywordChunks.stream().map(KbChunk::getId).collect(Collectors.toSet());
            Map<Long, Double> recallRelevance = new HashMap<>();
            for (KbChunk chunk : keywordChunks) {
                recallRelevance.merge(chunk.getId(), chunk.getRelevance() == null ? 0d : chunk.getRelevance(), Math::max);
            }
            double recallMaxRelevance = recallRelevance.values().stream().mapToDouble(Double::doubleValue).max().orElse(0d);

            // 合并去重
            Set<Long> allIds = new LinkedHashSet<>(vectorIds);
            List<KbChunk> vectorChunks = findKbChunks(vectorIds, kbId);
            for (KbChunk chunk : keywordChunks) {
                if (allIds.add(chunk.getId())) {
                    vectorChunks.add(chunk);
                    vectorScores.add(0.3f);
                }
            }

            // 重排序
            List<KbChunk> reranked = reranker.rerank(searchQuery, vectorChunks, vectorScores, topK);
            List<RetrievalCandidate> diagnostics = new ArrayList<>();
            for (KbChunk chunk : reranked) {
                boolean vectorMatched = vectorScoreByChunkId.containsKey(chunk.getId());
                boolean keywordMatched = keywordChunkIds.contains(chunk.getId());
                RetrievalCandidate candidate = new RetrievalCandidate(chunk);
                candidate.setVectorScore(vectorScoreByChunkId.getOrDefault(chunk.getId(), 0.3f));
                candidate.setKeywordScore(RetrievalCandidate.normalizeKeywordScore(
                        keywordMatched, recallRelevance.get(chunk.getId()), recallMaxRelevance));
                candidate.setMatchType(vectorMatched && keywordMatched ? "HYBRID"
                        : keywordMatched ? "KEYWORD" : "VECTOR");
                diagnostics.add(candidate);
            }
            List<RetrievalCandidate> selectedDiagnostics = retrievalSelector.select(diagnostics, topK);
            Set<Long> selectedIds = selectedDiagnostics.stream()
                    .map(candidate -> candidate.getChunk().getId()).collect(Collectors.toSet());
            Map<Long, RetrievalCandidate> diagnosticById = diagnostics.stream()
                    .collect(Collectors.toMap(candidate -> candidate.getChunk().getId(), candidate -> candidate));

            // 构建返回结果
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < reranked.size(); i++) {
                KbChunk chunk = reranked.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", i + 1);
                item.put("chunkId", chunk.getId());
                item.put("fileId", chunk.getFileId());
                item.put("fileName", chunk.getFileName());
                item.put("chunkIndex", chunk.getChunkIndex());
                item.put("source", chunk.getSourceInfo() != null ? chunk.getSourceInfo() : "未知来源");
                item.put("content", chunk.getContent());
                item.put("rewrittenQuery", searchQuery);
                item.put("vectorScore", vectorScoreByChunkId.get(chunk.getId()));
                boolean vectorMatched = vectorScoreByChunkId.containsKey(chunk.getId());
                boolean keywordMatched = keywordChunkIds.contains(chunk.getId());
                item.put("matchType", vectorMatched && keywordMatched ? "HYBRID"
                        : keywordMatched ? "KEYWORD" : "VECTOR");
                RetrievalCandidate diagnostic = diagnosticById.get(chunk.getId());
                item.put("keywordScore", diagnostic == null ? 0f : diagnostic.getKeywordScore());
                item.put("finalScore", diagnostic == null ? 0f : diagnostic.getFinalScore());
                item.put("selected", selectedIds.contains(chunk.getId()));
                item.put("filterReason", diagnostic == null ? null : diagnostic.getFilterReason());
                results.add(item);
            }
            return results;
        } catch (Exception e) {
            log.warn("Recall test failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 使用临时参数执行完整检索诊断，不调用大模型 */
    public Map<String, Object> diagnoseRecall(String message, Long kbId, Map<String, Object> request) {
        if (message == null || message.isBlank() || kbId == null) {
            return Map.of("originalQuery", message == null ? "" : message, "candidates", List.of());
        }
        try {
            int debugTopK = readInt(request.get("topK"), topK, 1, 50);
            int debugCandidateK = readInt(request.get("candidateK"), Math.max(candidateK, debugTopK * 2), debugTopK, 100);
            float threshold = readFloat(request.get("similarityThreshold"), 0.25f, 0f, 1f);
            float vectorWeight = readFloat(request.get("vectorWeight"), 0.6f, 0f, 1f);
            float keywordWeight = readFloat(request.get("keywordWeight"), 0.4f, 0f, 1f);
            String rewritten = queryRewriter.rewrite(message);
            String searchQuery = rewritten == null || rewritten.isBlank() ? message : rewritten;
            float[] queryEmbedding = embeddingService.embed(searchQuery);
            List<SearchResult> vectorResults = qdrantService.searchWithScore(queryEmbedding, debugCandidateK, kbId);
            Map<Long, Float> vectorScores = vectorResults.stream().collect(Collectors.toMap(result -> result.id, result -> result.score, Math::max));
            List<KbChunk> keywordChunks = keywordSearchService.search(searchQuery, kbId, debugCandidateK);
            Set<Long> keywordIds = keywordChunks.stream().map(KbChunk::getId).collect(Collectors.toSet());
            Map<Long, Double> debugRelevance = new HashMap<>();
            for (KbChunk chunk : keywordChunks) {
                debugRelevance.merge(chunk.getId(), chunk.getRelevance() == null ? 0d : chunk.getRelevance(), Math::max);
            }
            double debugMaxRelevance = debugRelevance.values().stream().mapToDouble(Double::doubleValue).max().orElse(0d);
            LinkedHashMap<Long, KbChunk> chunks = new LinkedHashMap<>();
            for (KbChunk chunk : findKbChunks(vectorResults.stream().map(result -> result.id).toList(), kbId)) chunks.put(chunk.getId(), chunk);
            for (KbChunk chunk : keywordChunks) chunks.putIfAbsent(chunk.getId(), chunk);
            List<RetrievalCandidate> candidates = new ArrayList<>();
            for (KbChunk chunk : chunks.values()) {
                RetrievalCandidate candidate = new RetrievalCandidate(chunk);
                boolean vectorMatched = vectorScores.containsKey(chunk.getId());
                boolean keywordMatched = keywordIds.contains(chunk.getId());
                candidate.setVectorScore(vectorScores.getOrDefault(chunk.getId(), 0.3f));
                candidate.setKeywordScore(RetrievalCandidate.normalizeKeywordScore(
                        keywordMatched, debugRelevance.get(chunk.getId()), debugMaxRelevance));
                candidate.setMatchType(vectorMatched && keywordMatched ? "HYBRID" : keywordMatched ? "KEYWORD" : "VECTOR");
                candidates.add(candidate);
            }
            retrievalSelector.select(candidates, debugTopK, threshold, vectorWeight, keywordWeight, 0.85f);
            candidates.sort(Comparator.comparing(RetrievalCandidate::getFinalScore).reversed());
            List<Map<String, Object>> items = new ArrayList<>();
            for (RetrievalCandidate candidate : candidates) {
                KbChunk chunk = candidate.getChunk();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", chunk.getId()); item.put("fileId", chunk.getFileId()); item.put("fileName", chunk.getFileName());
                item.put("chunkIndex", chunk.getChunkIndex()); item.put("source", chunk.getSourceInfo()); item.put("content", chunk.getContent());
                item.put("vectorScore", candidate.getVectorScore()); item.put("keywordScore", candidate.getKeywordScore());
                item.put("finalScore", candidate.getFinalScore()); item.put("matchType", candidate.getMatchType());
                item.put("selected", candidate.isSelected()); item.put("filterReason", candidate.getFilterReason()); items.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("originalQuery", message); result.put("rewrittenQuery", searchQuery);
            result.put("parameters", Map.of("topK", debugTopK, "candidateK", debugCandidateK, "similarityThreshold", threshold,
                    "vectorWeight", vectorWeight, "keywordWeight", keywordWeight));
            result.put("candidateCount", items.size()); result.put("selectedCount", items.stream().filter(item -> Boolean.TRUE.equals(item.get("selected"))).count());
            result.put("candidates", items); return result;
        } catch (Exception e) {
            log.warn("Retrieval diagnosis failed: {}", e.getMessage());
            throw new RuntimeException("检索诊断失败", e);
        }
    }

    /** 读取带范围限制的整数参数 */
    private int readInt(Object value, int defaultValue, int min, int max) {
        if (value == null) return defaultValue;
        int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        return Math.max(min, Math.min(max, parsed));
    }

    /** 读取带范围限制的小数参数 */
    private float readFloat(Object value, float defaultValue, float min, float max) {
        if (value == null) return defaultValue;
        float parsed = value instanceof Number number ? number.floatValue() : Float.parseFloat(value.toString());
        return Math.max(min, Math.min(max, parsed));
    }

    // ==================== 用量记录 ====================

    /**
     * 记录一次 LLM 调用的用量信息
     * 包括供应商信息、Token 消耗、响应耗时和调用状态
     *
     * @param sessionId      会话ID
     * @param provider       大模型供应商配置
     * @param startTime      调用开始时间戳（毫秒）
     * @param status         调用状态：SUCCESS 或 FAILED
     * @param promptTokens   输入 Token 数
     * @param completionTokens 输出 Token 数
     */
    private void recordUsage(Long sessionId, ModelProvider provider, long startTime,
                             String status, int promptTokens, int completionTokens) {
        try {
            UsageLog logEntry = new UsageLog();
            logEntry.setSessionId(sessionId);
            logEntry.setProviderId(provider.getId());
            logEntry.setProviderName(provider.getName());
            logEntry.setModel(provider.getModel());
            logEntry.setPromptTokens(promptTokens);
            logEntry.setCompletionTokens(completionTokens);
            logEntry.setTotalTokens(promptTokens + completionTokens);
            logEntry.setDurationMs((int) (System.currentTimeMillis() - startTime));
            logEntry.setStatus(status);
            usageService.record(logEntry);
        } catch (Exception e) {
            log.error("Failed to record usage", e);
        }
    }

    /**
     * LLM 调用结果内部类
     * 封装大模型返回的回复内容和 Token 用量统计
     */
    private static class LlmResult {
        /** 模型生成的回复文本 */
        final String content;
        /** 输入 Token 数（prompt_tokens） */
        final int promptTokens;
        /** 输出 Token 数（completion_tokens） */
        final int completionTokens;

        LlmResult(String content, int promptTokens, int completionTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }
    }

    // ==================== LangChain4j 调用辅助 ====================

    /**
     * 根据数据库中的供应商配置创建 LangChain4j 同步聊天模型
     *
     * @param provider 大模型供应商配置
     * @return LangChain4j OpenAiChatModel
     */
    private OpenAiChatModel createLangChain4jChatModel(ModelProvider provider) {
        // 第1步：规范化 baseUrl，兼容用户填写完整 chat completions 地址
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        // 第2步：创建 LangChain4j 同步模型，显式设置超时，供应商无响应时 120 秒内快速失败
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 根据数据库中的供应商配置创建 LangChain4j 流式聊天模型
     *
     * @param provider 大模型供应商配置
     * @return LangChain4j OpenAiStreamingChatModel
     */
    private OpenAiStreamingChatModel createLangChain4jStreamingChatModel(ModelProvider provider) {
        // 第1步：规范化 baseUrl，兼容用户填写完整 chat completions 地址
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        // 第2步：创建 LangChain4j 流式模型，显式设置超时，与同步路径保持一致
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 规范化 OpenAI 兼容接口的 baseUrl
     *
     * @param rawBaseUrl 用户配置的 API 地址
     * @return 可以交给 LangChain4j 的 baseUrl
     */
    private String normalizeOpenAiBaseUrl(String rawBaseUrl) {
        // 第1步：去掉末尾多余斜杠
        String baseUrl = rawBaseUrl.replaceAll("/+$", "");
        // 第2步：如果用户填了完整 chat/completions 地址，就截断到模型服务基础路径
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }
        // 第3步：如果用户已经填到 /v1，就直接交给 LangChain4j
        if (baseUrl.endsWith("/v1")) {
            return baseUrl;
        }
        // 第4步：裸域名默认补齐 /v1，和系统里手写 HTTP 模型调用规则保持一致
        if (!baseUrl.matches(".*/v\\d+(?:/.*)?$")) {
            return baseUrl + "/v1";
        }
        // 第5步：其他带版本路径的地址保持原样
        return baseUrl;
    }

    /**
     * 从 LangChain4j 响应中提取文本
     *
     * @param response LangChain4j 聊天响应
     * @return 模型返回的文本
     */
    private String extractResponseText(Response<AiMessage> response) {
        // 第1步：空响应直接返回空字符串
        if (response == null || response.content() == null) {
            return "";
        }
        // 第2步：从 AiMessage 中读取文本
        String text = response.content().text();
        // 第3步：空文本统一转为空字符串
        return text == null ? "" : text;
    }

    /**
     * 从 LangChain4j 响应中提取 Token 用量
     *
     * @param response LangChain4j 聊天响应
     * @param tokenUsage Token 用量数组，[0] 输入 Token，[1] 输出 Token
     */
    private void parseUsage(Response<AiMessage> response, int[] tokenUsage) {
        // 第1步：响应为空时直接返回
        if (response == null) {
            return;
        }
        // 第2步：读取 LangChain4j 统一用量对象
        TokenUsage usage = response.tokenUsage();
        if (usage == null) {
            return;
        }
        // 第3步：输入 Token 存在时写入缓存
        if (usage.inputTokenCount() != null) {
            tokenUsage[0] = usage.inputTokenCount();
        }
        // 第4步：输出 Token 存在时写入缓存
        if (usage.outputTokenCount() != null) {
            tokenUsage[1] = usage.outputTokenCount();
        }
    }

    /**
     * 流式输出异常时，向前端推送错误信息并关闭 SSE 连接
     *
     * @param emitter SSE 推送对象
     * @param e       异常信息
     */
    private void completeEmitterWithError(SseEmitter emitter, Exception e) {
        safeSend(emitter, SseEmitter.event().name("error").data(buildUserErrorMessage(e)));
        safeErrorComplete(emitter, e);
    }

    /**
     * 安全发送 SSE 事件（前端可能已断开连接，需要捕获异常）
     *
     * @param emitter SSE 推送对象
     * @param event   SSE 事件构建器
     */
    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try { emitter.send(event); } catch (Exception e) { log.debug("SseEmitter already closed"); }
    }

    /**
     * 安全发送 JSON 格式 SSE 事件
     */
    private void safeSendJson(SseEmitter emitter, String eventName, Object data) {
        try {
            safeSend(emitter, SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("Failed to serialize SSE event {}", eventName);
        }
    }

    /**
     * 安全关闭 SSE 连接（正常完成）
     *
     * @param emitter SSE 推送对象
     */
    private void safeComplete(SseEmitter emitter) {
        try { emitter.complete(); } catch (Exception e) { log.debug("SseEmitter already closed"); }
    }

    /**
     * 安全关闭 SSE 连接（异常完成）
     *
     * @param emitter SSE 推送对象
     * @param e       异常信息
     */
    private void safeErrorComplete(SseEmitter emitter, Exception e) {
        try { emitter.completeWithError(e); } catch (Exception ex) { log.debug("SseEmitter already closed"); }
    }

    /**
     * 构建面向用户的错误提示信息
     * 截断过长的错误详情，添加友好的前缀
     *
     * @param e 异常对象
     * @return 用户可读的错误提示
     */
    private String buildUserErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) { return "聊天服务异常，请稍后重试"; }
        if (message.length() > 500) { message = message.substring(0, 500) + "..."; }
        return "聊天服务异常：" + message;
    }

    /**
     * 安全处理文本（去掉换行符，避免日志格式被打乱）
     *
     * @param text 原始文本
     * @return 处理后的文本，null 转为空字符串
     */
    private String safeText(String text) {
        if (text == null) { return ""; }
        return text.replace("\r", "").replace("\n", "");
    }

    // ==================== 消息持久化 ====================

    /**
     * 保存一条聊天消息到数据库
     * 保存失败时只记录日志，不影响主流程
     *
     * @param userId    当前用户ID
     * @param sessionId 会话ID
     * @param role      消息角色：user 或 assistant
     * @param content   消息内容
     */
    private Long saveMessage(Long userId, Long sessionId, String role, String content) {
        try {
            com.rag.entity.ChatMessage msg = new com.rag.entity.ChatMessage();
            msg.setUserId(userId);
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            chatMessageMapper.insert(msg);
            return msg.getId();
        } catch (Exception e) {
            log.error("Failed to save chat message", e);
            return null;
        }
    }

    /** 保存知识库回答质量事实，写入失败不影响聊天主流程。 */
    private void recordAnswerQuality(Long answerMessageId, Long sessionId, Long kbId, String question,
                                     Integer confidence, Integer evidenceCoverage, boolean grounded,
                                     boolean refusal, String reason) {
        if (answerQualityMapper == null || answerMessageId == null || sessionId == null || kbId == null) {
            return;
        }
        try {
            com.rag.entity.AnswerQuality quality = new com.rag.entity.AnswerQuality();
            quality.setAnswerMessageId(answerMessageId);
            quality.setSessionId(sessionId);
            quality.setKbId(kbId);
            quality.setQuestion(question == null ? "" : question.trim());
            quality.setConfidence(confidence == null ? 0 : confidence);
            quality.setEvidenceCoverage(evidenceCoverage == null ? 0 : evidenceCoverage);
            quality.setGrounded(grounded);
            quality.setRefusal(refusal);
            quality.setReason(reason);
            answerQualityMapper.insert(quality);
        } catch (Exception e) {
            log.error("Failed to save answer quality fact", e);
        }
    }

    /**
     * 获取某个会话的所有聊天记录（按时间正序）
     *
     * @param userId    当前用户ID
     * @param sessionId 会话ID
     * @return 消息列表
     */
    public List<com.rag.entity.ChatMessage> getSessionMessages(Long userId, Long sessionId) {
        List<com.rag.entity.ChatMessage> messages =
                chatMessageMapper.findByUserIdAndSessionId(userId, sessionId);
        // 附带每条消息的反馈状态：数据库异常按无反馈处理，不影响历史加载
        try {
            java.util.Map<Long, Integer> feedbackMap = new java.util.HashMap<>();
            for (com.rag.entity.Feedback feedback : feedbackMapper.findByChatSessionId(sessionId, userId)) {
                feedbackMap.put(feedback.getMessageId(), feedback.getHelpful());
            }
            for (com.rag.entity.ChatMessage message : messages) {
                if (feedbackMap.containsKey(message.getId())) {
                    message.setHelpful(feedbackMap.get(message.getId()));
                }
            }
        } catch (Exception e) {
            log.warn("读取会话反馈状态失败，按无反馈返回: {}", e.getMessage());
        }
        return messages;
    }

    /**
     * 删除某个会话的所有聊天记录
     *
     * @param userId    当前用户ID
     * @param sessionId 会话ID
     */
    public void deleteSession(Long userId, Long sessionId) {
        chatMessageMapper.deleteByUserIdAndSessionId(userId, sessionId);
    }
}
