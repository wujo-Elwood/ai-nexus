package com.rag.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.entity.KbChunk;
import com.rag.entity.ModelProvider;
import com.rag.entity.UsageLog;
import com.rag.mapper.ChatMessageMapper;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.KnowledgeBaseMapper;
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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

    /** 多轮对话：最多加载的历史消息条数 */
    private static final int MAX_HISTORY_MESSAGES = 10;

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
    /** 检索候选选择器 */
    @org.springframework.beans.factory.annotation.Autowired
    private RetrievalSelector retrievalSelector;
    /** 知识库级检索策略读取器 */
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    /** 可信回答校验器 */
    @org.springframework.beans.factory.annotation.Autowired
    private TrustedAnswerGuard trustedAnswerGuard;
    /** SSE 结构化事件序列化器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统提示词：要求模型基于知识库上下文回答并标注来源 */
    private static final String SYSTEM_PROMPT = "你是一个智能 AI 助手。\n\n"
            + "如果提供了知识库上下文，请优先基于上下文回答问题。\n"
            + "回答时请在相关语句后用 [来源: 文件名, 第N段] 标注引用来源。\n"
            + "如果没有提供知识库上下文，请直接使用大模型自身知识回答。\n"
            + "回答要简洁、准确、有帮助。";

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
     * @param sessionId 会话ID
     * @param message   用户发送的消息内容
     * @param kbId      知识库ID（可选，为空则不检索知识库）
     * @return 大模型生成的回复文本
     */
    public String chat(Long sessionId, String message, Long kbId) {
        // 安全检查：拦截 Prompt 注入攻击
        promptGuard.check(message);
        // 获取当前激活的大模型供应商配置
        ModelProvider provider = modelProviderService.getActive();
        // 保存用户消息到数据库
        saveMessage(sessionId, "user", message);

        // 混合检索 + 重排序，构建带来源信息的知识库上下文
        KnowledgeContext context = buildContextWithSources(message, kbId);
        //知识库模式没有证据时直接拒答，不调用大模型
        if (kbId != null && !context.hasContext()) {
            String refusal = trustedAnswerGuard.validate("", context).getContent();
            saveMessage(sessionId, "assistant", refusal);
            recordUsage(sessionId, provider, System.currentTimeMillis(), "SUCCESS", 0, 0);
            return refusal;
        }
        // 组装 system + 历史消息 + 当前问题的 LangChain4j 消息列表
        List<dev.langchain4j.data.message.ChatMessage> chatMessages = buildChatMessages(sessionId, message, context);

        long startTime = System.currentTimeMillis();
        try {
            // 使用 LangChain4j 同步调用大模型，等待完整回复
            LlmResult llmResult = callLlmSync(chatMessages, provider);
            String answer = llmResult.content;
            if (kbId != null) {
                //知识库模式只保存经过来源校验的回答
                answer = trustedAnswerGuard.validate(answer, context).getContent();
            }
            // 保存助手回复到数据库
            saveMessage(sessionId, "assistant", answer);
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
     * @param sessionId 会话ID
     * @param message   用户发送的消息内容
     * @param kbId      知识库ID（可选）
     * @return SSE 推送对象，前端通过此对象接收流式数据
     */
    public SseEmitter chatStream(Long sessionId, String message, Long kbId) {
        // 安全检查：拦截 Prompt 注入攻击
        promptGuard.check(message);
        // 获取当前激活的大模型供应商配置
        ModelProvider provider = modelProviderService.getActive();
        // 保存用户消息到数据库
        saveMessage(sessionId, "user", message);
        // 创建 SSE 推送对象（超时设为 0 表示不超时）
        SseEmitter emitter = new SseEmitter(0L);
        // 把检索和大模型调用放到后台线程执行，不阻塞主线程
        chatStreamExecutor.execute(() -> prepareAndStreamResponse(sessionId, message, kbId, emitter, provider));
        // 立即返回 SSE 对象给控制器
        return emitter;
    }

    /**
     * 准备上下文并开始流式推送（在后台线程中执行）
     * 流程：检索知识库 → 构建消息 → 流式调用大模型 → 推送 token 到前端
     *
     * @param sessionId 会话ID
     * @param message   用户消息
     * @param kbId      知识库ID
     * @param emitter   SSE 推送对象
     * @param provider  大模型供应商配置
     */
    private void prepareAndStreamResponse(Long sessionId, String message, Long kbId,
                                          SseEmitter emitter, ModelProvider provider) {
        long startTime = System.currentTimeMillis();
        try {
            // 通知前端正在检索知识库
            safeSend(emitter, SseEmitter.event().name("status").data("正在检索知识库并生成内容..."));
            // 混合检索 + 重排序，构建知识库上下文
            KnowledgeContext context = buildContextWithSources(message, kbId);
            if (kbId != null) {
                //先发送引用元数据，前端可以在回答下方展示参考资料
                safeSendJson(emitter, "citation", context.getCitations());
                if (!context.hasContext()) {
                    String refusal = trustedAnswerGuard.validate("", context).getContent();
                    saveMessage(sessionId, "assistant", refusal);
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
            List<dev.langchain4j.data.message.ChatMessage> chatMessages = buildChatMessages(sessionId, message, context);
            // 通知前端正在生成
            safeSend(emitter, SseEmitter.event().name("status").data("正在生成..."));
            // 使用 LangChain4j 流式调用大模型，逐段推送到前端
            LlmResult llmResult = streamLlmResponse(sessionId, chatMessages, emitter, provider, context);
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
     * @param sessionId 会话ID
     * @param chatMessages 组装好的 LangChain4j 消息列表
     * @param emitter   SSE 推送对象
     * @param provider  大模型供应商配置
     * @return LlmResult 包含完整回复内容和 Token 用量
     */
    private LlmResult streamLlmResponse(Long sessionId, List<dev.langchain4j.data.message.ChatMessage> chatMessages,
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
                        answer = trusted.getContent();
                        saveMessage(sessionId, "assistant", answer);
                        safeSend(emitter, SseEmitter.event().name("message").data(answer));
                        // 计算可信回答的置信度、证据覆盖率和引用数量
                        Map<String, Object> answerMeta = new LinkedHashMap<>(AnswerEvidenceMetrics.calculate(
                                answer, trusted.isGrounded(), trusted.getCitations()));
                        answerMeta.put("grounded", trusted.isGrounded());
                        answerMeta.put("reason", trusted.getReason());
                        safeSendJson(emitter, "answer-meta", answerMeta);
                    } else if (!fullResponse.isEmpty()) {
                        //普通聊天保持原有保存逻辑
                        saveMessage(sessionId, "assistant", answer);
                    }
                    // 第3步：通知前端流式输出完成
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
                        saveMessage(sessionId, "assistant", fullResponse.toString());
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
                saveMessage(sessionId, "assistant", fullResponse.toString());
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
     * @param sessionId    会话ID（用于加载历史消息）
     * @param message      当前用户问题
     * @param context 知识库检索结果（含来源标注）
     * @return 消息数组，按 system → history → user 排列
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildChatMessages(Long sessionId, String message, KnowledgeContext context) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // 第1步：构建系统提示词
        String systemContent = SYSTEM_PROMPT;
        if (context.hasContext()) {
            // 第2步：对检索到的上下文进行压缩，减少 token 消耗
            String compressedContext = contextCompressor.compress(message, context.getContextWithSources());
            systemContent += "\n\n以下是知识库中的相关内容：\n\n" + compressedContext
                    + "\n\n只能依据以上内容回答，并且必须在相关语句后使用准确的 [来源: 文件名, 第N段] 引用。没有依据时不要补充外部知识。";
        }
        // 第3步：把系统提示词加入 LangChain4j 消息列表
        messages.add(SystemMessage.from(systemContent));

        // 第4步：加载最近的历史消息
        List<com.rag.entity.ChatMessage> history = chatMessageMapper.findBySessionId(sessionId);
        if (history.size() > 1) {
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
     * @return ContextResult 包含纯文本上下文和带来源标注的上下文
     */
    private KnowledgeContext buildContextWithSources(String message, Long kbId) {
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
            String rewrittenQuery = queryRewriter.rewrite(message);
            String searchQuery = rewrittenQuery.isEmpty() ? message : rewrittenQuery;

            // 第2步：检查缓存（相同问题 5 分钟内直接返回缓存结果）
            List<Long> cachedIds = retrievalCache.get(kbId, searchQuery);
            if (cachedIds != null && !cachedIds.isEmpty()) {
                List<KbChunk> cachedChunks = findKbChunks(cachedIds, kbId);
                if (!cachedChunks.isEmpty()) {
                    return buildKnowledgeContext(cachedChunks, searchQuery, Collections.emptyMap(), Collections.emptySet());
                }
            }

            // 第3步：向量检索（语义相似度匹配，多取一些用于后续筛选）
            float[] queryEmbedding = embeddingService.embed(searchQuery);
            int effectiveCandidateK = Math.max(candidateK, effectiveTopK * 2);
            List<SearchResult> vectorResults = qdrantService.searchWithScore(queryEmbedding, effectiveCandidateK, kbId);
            List<Long> vectorIds = vectorResults.stream().map(r -> r.id).collect(Collectors.toList());
            Set<Long> vectorIdSet = new HashSet<>(vectorIds);
            Map<Long, Float> vectorScoreById = vectorResults.stream()
                    .collect(Collectors.toMap(result -> result.id, result -> result.score, Math::max));

            // 第4步：关键词检索（多个关键词分别查询后合并）
            List<KbChunk> keywordChunks = keywordSearchService.search(searchQuery, kbId, effectiveCandidateK);
            Set<Long> keywordIds = keywordChunks.stream().map(KbChunk::getId).collect(Collectors.toSet());

            // 第5步：合并去重（向量结果 + 关键词结果，去掉重复的 chunk）
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

            // 第6步：融合分数、过滤低分结果并去除高度重复片段
            List<RetrievalCandidate> candidates = new ArrayList<>();
            for (KbChunk chunk : vectorChunks) {
                boolean keywordMatched = keywordIds.contains(chunk.getId());
                boolean vectorMatched = vectorIdSet.contains(chunk.getId());
                RetrievalCandidate candidate = new RetrievalCandidate(chunk);
                candidate.setVectorScore(vectorScoreById.getOrDefault(chunk.getId(), 0.3f));
                candidate.setKeywordScore(keywordMatched ? 1f : 0f);
                candidate.setMatchType(vectorMatched && keywordMatched ? "HYBRID"
                        : keywordMatched ? "KEYWORD" : "VECTOR");
                candidates.add(candidate);
            }
            List<RetrievalCandidate> selected = retrievalSelector.select(candidates, effectiveTopK,
                    threshold, vectorWeight, keywordWeight, 0.85f);
            if (selected.isEmpty()) {
                return KnowledgeContext.empty();
            }

            // 第7步：写入缓存（下次相同问题直接命中）
            List<Long> selectedIds = selected.stream().map(candidate -> candidate.getChunk().getId()).collect(Collectors.toList());
            retrievalCache.put(kbId, searchQuery, selectedIds);

            return buildKnowledgeContext(selected, searchQuery);
        } catch (Exception e) {
            log.error("Failed to build knowledge context", e);
            throw new RuntimeException("Knowledge retrieval failed", e);
        }
    }

    /**
     * 从 chunk 列表构建带来源信息的上下文结果
     * 生成两个版本：纯文本版（用于缓存）和带来源标注版（用于 prompt）
     *
     * @param chunks 重排序后的 chunk 列表
     * @return ContextResult 包含两种格式的上下文文本
     */
    private KnowledgeContext buildKnowledgeContext(List<KbChunk> chunks, String rewrittenQuery,
                                                   Map<Long, Float> vectorScores, Set<Long> keywordIds) {
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
            citations.add(citation);
        }
        return new KnowledgeContext(contextText.toString(), contextWithSources.toString(), citations, rewrittenQuery);
    }

    /**
     * 从带分数的检索候选构建知识库上下文
     */
    private KnowledgeContext buildKnowledgeContext(List<RetrievalCandidate> candidates, String rewrittenQuery) {
        List<KbChunk> chunks = candidates.stream().map(RetrievalCandidate::getChunk).toList();
        Map<Long, Float> vectorScores = new HashMap<>();
        Set<Long> keywordIds = new HashSet<>();
        for (RetrievalCandidate candidate : candidates) {
            vectorScores.put(candidate.getChunk().getId(), candidate.getVectorScore());
            if (candidate.getKeywordScore() > 0) {
                keywordIds.add(candidate.getChunk().getId());
            }
        }
        KnowledgeContext context = buildKnowledgeContext(chunks, rewrittenQuery, vectorScores, keywordIds);
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
                candidate.setKeywordScore(keywordMatched ? 1f : 0f);
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
            LinkedHashMap<Long, KbChunk> chunks = new LinkedHashMap<>();
            for (KbChunk chunk : findKbChunks(vectorResults.stream().map(result -> result.id).toList(), kbId)) chunks.put(chunk.getId(), chunk);
            for (KbChunk chunk : keywordChunks) chunks.putIfAbsent(chunk.getId(), chunk);
            List<RetrievalCandidate> candidates = new ArrayList<>();
            for (KbChunk chunk : chunks.values()) {
                RetrievalCandidate candidate = new RetrievalCandidate(chunk);
                boolean vectorMatched = vectorScores.containsKey(chunk.getId());
                boolean keywordMatched = keywordIds.contains(chunk.getId());
                candidate.setVectorScore(vectorScores.getOrDefault(chunk.getId(), 0.3f));
                candidate.setKeywordScore(keywordMatched ? 1f : 0f);
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
        // 第2步：创建 LangChain4j 同步模型
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(0.3)
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
        // 第2步：创建 LangChain4j 流式模型
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(0.3)
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
     * @param sessionId 会话ID
     * @param role      消息角色：user 或 assistant
     * @param content   消息内容
     */
    private void saveMessage(Long sessionId, String role, String content) {
        try {
            com.rag.entity.ChatMessage msg = new com.rag.entity.ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            chatMessageMapper.insert(msg);
        } catch (Exception e) {
            log.error("Failed to save chat message", e);
        }
    }

    /**
     * 获取某个会话的所有聊天记录（按时间正序）
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    public List<com.rag.entity.ChatMessage> getSessionMessages(Long sessionId) {
        return chatMessageMapper.findBySessionId(sessionId);
    }

    /**
     * 删除某个会话的所有聊天记录
     *
     * @param sessionId 会话ID
     */
    public void deleteSession(Long sessionId) {
        chatMessageMapper.deleteBySessionId(sessionId);
    }
}
