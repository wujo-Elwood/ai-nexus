package com.rag.ai;

import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.ModelProvider;
import com.rag.mapper.ChunkMapper;
import com.rag.rag.ContextCompressor;
import com.rag.rag.KnowledgeContext;
import com.rag.rag.KeywordSearchService;
import com.rag.rag.PromptGuard;
import com.rag.rag.QdrantService;
import com.rag.rag.QueryRewriter;
import com.rag.rag.Reranker;
import com.rag.rag.RetrievalCache;
import com.rag.service.ModelProviderService;
import com.rag.service.UsageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天服务测试
 * 验证聊天服务中模型地址归一化逻辑
 */
class ChatServiceTest {

    /**
     * 测试裸域名会自动补齐 OpenAI 兼容接口版本路径
     */
    @Test
    void normalizeOpenAiBaseUrlShouldAppendV1ForBareDomain() throws Exception {
        // 第1步：创建只用于调用私有方法的服务对象
        ChatService chatService = new ChatService(null, null, null, null, null, null, null, null, null, null, null, null, null);
        // 第2步：通过反射读取私有的地址归一化方法
        Method normalizeMethod = ChatService.class.getDeclaredMethod("normalizeOpenAiBaseUrl", String.class);
        normalizeMethod.setAccessible(true);
        // 第3步：验证裸域名会补齐 /v1
        String normalizedUrl = (String) normalizeMethod.invoke(chatService, "https://zyyc.mxou.cn");
        assertEquals("https://zyyc.mxou.cn/v1", normalizedUrl);
    }

    /**
     * 测试完整聊天接口地址会被截断为 LangChain4j 需要的 baseUrl
     */
    @Test
    void normalizeOpenAiBaseUrlShouldTrimChatCompletionsPath() throws Exception {
        // 第1步：创建只用于调用私有方法的服务对象
        ChatService chatService = new ChatService(null, null, null, null, null, null, null, null, null, null, null, null, null);
        // 第2步：通过反射读取私有的地址归一化方法
        Method normalizeMethod = ChatService.class.getDeclaredMethod("normalizeOpenAiBaseUrl", String.class);
        normalizeMethod.setAccessible(true);
        // 第3步：验证完整接口地址会转换为 /v1 级别的 baseUrl
        String normalizedUrl = (String) normalizeMethod.invoke(chatService, "https://zyyc.mxou.cn/v1/chat/completions");
        assertEquals("https://zyyc.mxou.cn/v1", normalizedUrl);
    }

    /**
     * 测试召回诊断返回文件、文本块、查询和向量分数
     */
    @Test
    void recallTestShouldReturnRetrievalDiagnostics() {
        // 第1步：准备一条向量召回结果和对应文本块
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        QdrantService qdrantService = mock(QdrantService.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        Reranker reranker = mock(Reranker.class);
        KeywordSearchService keywordSearchService = mock(KeywordSearchService.class);
        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        KbChunk chunk = new KbChunk();
        chunk.setId(8L);
        chunk.setFileId(4L);
        chunk.setFileName("员工制度.pdf");
        chunk.setChunkIndex(2);
        chunk.setSourceInfo("员工制度.pdf, 第3段");
        chunk.setContent("员工年假按照工龄计算。 ");
        when(queryRewriter.rewrite("年假多少天")).thenReturn("员工年假天数");
        when(embeddingService.embed("员工年假天数")).thenReturn(new float[]{0.1f, 0.2f});
        when(qdrantService.searchWithScore(any(float[].class), eq(10), eq(3L)))
                .thenReturn(List.of(new QdrantService.SearchResult(8L, 0.86f)));
        when(chunkMapper.findByIdAndKbId(8L, 3L)).thenReturn(chunk);
        when(keywordSearchService.search("员工年假天数", 3L, 5)).thenReturn(Collections.emptyList());
        when(reranker.rerank(eq("员工年假天数"), any(), any(), eq(5))).thenReturn(List.of(chunk));

        // 第2步：组装聊天服务并执行召回测试
        ChatService service = new ChatService(embeddingService, qdrantService, chunkMapper, null,
                mock(ModelProviderService.class), reranker, keywordSearchService, mock(UsageService.class),
                mock(PromptGuard.class), queryRewriter, mock(RetrievalCache.class),
                mock(ContextCompressor.class), Runnable::run);
        // 第3步：打开查询改写以验证诊断接口仍支持改写结果
        ReflectionTestUtils.setField(service, "queryRewriteEnabled", true);
        ReflectionTestUtils.setField(service, "topK", 5);
        List<Map<String, Object>> results = service.recallTest("年假多少天", 3L);

        // 第3步：确认诊断字段完整且数值来自真实向量召回
        Map<String, Object> result = results.get(0);
        assertEquals("员工年假天数", result.get("rewrittenQuery"));
        assertEquals("员工制度.pdf", result.get("fileName"));
        assertEquals(2, result.get("chunkIndex"));
        assertEquals(0.86f, result.get("vectorScore"));
        assertEquals("VECTOR", result.get("matchType"));
        assertEquals(true, result.get("selected"));
    }

    /**
     * 测试知识库没有证据时直接拒答，不进入重排序和模型生成
     */
    @Test
    void chatShouldRefuseWhenKnowledgeBaseHasNoEvidence() {
        // 第1步：准备一个没有向量和关键词召回结果的知识库查询
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        QdrantService qdrantService = mock(QdrantService.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        com.rag.mapper.ChatMessageMapper messageMapper = mock(com.rag.mapper.ChatMessageMapper.class);
        ModelProviderService providerService = mock(ModelProviderService.class);
        Reranker reranker = mock(Reranker.class);
        KeywordSearchService keywordSearchService = mock(KeywordSearchService.class);
        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        UsageService usageService = mock(UsageService.class);
        ModelProvider provider = new ModelProvider();
        provider.setId(1L);
        provider.setName("test");
        provider.setModel("test-model");
        when(providerService.getActive()).thenReturn(provider);
        when(queryRewriter.rewrite("公司有没有住房补贴")).thenReturn("公司住房补贴");
        when(embeddingService.embed("公司住房补贴")).thenReturn(new float[]{0.1f, 0.2f});
        when(qdrantService.searchWithScore(any(float[].class), eq(10), eq(3L))).thenReturn(Collections.emptyList());
        when(qdrantService.search(any(float[].class), eq(5))).thenReturn(Collections.emptyList());
        when(keywordSearchService.search("公司住房补贴", 3L, 10)).thenReturn(Collections.emptyList());

        // 第2步：执行知识库问答
        ChatService service = new ChatService(embeddingService, qdrantService, chunkMapper, messageMapper,
                providerService, reranker, keywordSearchService, usageService, mock(PromptGuard.class),
                queryRewriter, retrievalCache, mock(ContextCompressor.class), Runnable::run);
        // 第3步：打开查询改写以保持该测试对改写失败拒答链路的覆盖
        ReflectionTestUtils.setField(service, "queryRewriteEnabled", true);
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "candidateK", 10);
        String answer = service.chat(99L, "公司有没有住房补贴", 3L);

        // 第3步：确认返回固定拒答且没有执行重排序
        assertEquals("知识库中没有找到足够依据，暂时无法可靠回答这个问题。", answer);
        verify(reranker, never()).rerank(any(), any(), any(), anyInt());
    }

    /**
     * 测试向量维度不匹配的业务异常原样透传，不被包装成笼统的检索失败
     */
    @Test
    void buildContextShouldRethrowBusinessExceptionAsIs() throws Exception {
        // 第1步：准备向量检索直接抛出维度不匹配业务异常的场景
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        QdrantService qdrantService = mock(QdrantService.class);
        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        com.rag.mapper.KnowledgeBaseMapper knowledgeBaseMapper = mock(com.rag.mapper.KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.findById(3L)).thenReturn(new com.rag.entity.KnowledgeBase());
        when(queryRewriter.rewrite("公司有没有住房补贴")).thenReturn("公司住房补贴");
        when(embeddingService.embed("公司住房补贴")).thenReturn(new float[]{0.1f, 0.2f});
        BusinessException mismatch = new BusinessException(500, "向量维度不匹配：向量库为 1024 维，当前 Embedding 模型输出 768 维");
        when(qdrantService.searchWithScore(any(float[].class), anyInt(), eq(3L))).thenThrow(mismatch);

        // 第2步：通过反射执行私有的知识库上下文构建方法
        ChatService service = new ChatService(embeddingService, qdrantService, null, null,
                mock(ModelProviderService.class), mock(Reranker.class), mock(KeywordSearchService.class),
                mock(UsageService.class), mock(PromptGuard.class), queryRewriter, retrievalCache,
                mock(ContextCompressor.class), Runnable::run);
        // 第3步：打开查询改写以验证业务异常透传链路
        ReflectionTestUtils.setField(service, "queryRewriteEnabled", true);
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", knowledgeBaseMapper);
        Method buildContext = ChatService.class.getDeclaredMethod("buildContextWithSources", String.class, Long.class);
        buildContext.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> buildContext.invoke(service, "公司有没有住房补贴", 3L));

        // 第3步：确认业务异常原样透传而不是被包装成 RuntimeException
        assertSame(mismatch, thrown.getCause());
    }

    /**
     * 测试关闭查询改写后，知识库检索直接使用用户原问题，避免额外调用一次大模型。
     */
    @Test
    void buildContextShouldUseOriginalQueryWhenRewriteDisabled() throws Exception {
        // 第1步：准备一条可以被向量检索命中的文本块
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        QdrantService qdrantService = mock(QdrantService.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        RetrievalCache retrievalCache = mock(RetrievalCache.class);
        KeywordSearchService keywordSearchService = mock(KeywordSearchService.class);
        KbChunk chunk = new KbChunk();
        chunk.setId(8L);
        chunk.setFileId(4L);
        chunk.setFileName("员工制度.pdf");
        chunk.setChunkIndex(2);
        chunk.setSourceInfo("员工制度.pdf, 第3段");
        chunk.setContent("员工年假按照工龄计算。");
        when(embeddingService.embed("年假多少天")).thenReturn(new float[]{0.1f, 0.2f});
        when(qdrantService.searchWithScore(any(float[].class), eq(10), eq(3L)))
                .thenReturn(List.of(new QdrantService.SearchResult(8L, 0.86f)));
        when(chunkMapper.findByIdAndKbId(8L, 3L)).thenReturn(chunk);
        when(keywordSearchService.search("年假多少天", 3L, 10)).thenReturn(Collections.emptyList());

        // 第2步：组装服务并关闭查询改写
        ChatService service = new ChatService(embeddingService, qdrantService, chunkMapper, null,
                mock(ModelProviderService.class), mock(Reranker.class), keywordSearchService,
                mock(UsageService.class), mock(PromptGuard.class), queryRewriter, retrievalCache,
                mock(ContextCompressor.class), Runnable::run);
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "candidateK", 10);
        ReflectionTestUtils.setField(service, "queryRewriteEnabled", false);
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mock(com.rag.mapper.KnowledgeBaseMapper.class));

        // 第3步：执行私有检索流程并确认没有调用查询改写模型
        Method buildContext = ChatService.class.getDeclaredMethod("buildContextWithSources", String.class, Long.class);
        buildContext.setAccessible(true);
        KnowledgeContext context = (KnowledgeContext) buildContext.invoke(service, "年假多少天", 3L);

        // 第4步：确认检索上下文有效且改写器未被调用
        assertTrue(context.hasContext());
        assertEquals("年假多少天", context.getRewrittenQuery());
        verify(queryRewriter, never()).rewrite(any());
    }

    /**
     * 测试回答正文清理来源标记后仍保持普通文本内容。
     */
    @Test
    void removeSourceMarkersShouldKeepAnswerText() throws Exception {
        // 第1步：创建只用于调用私有清理方法的服务对象
        ChatService service = new ChatService(null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        // 第2步：通过反射读取私有来源标记清理方法
        Method method = ChatService.class.getDeclaredMethod("removeSourceMarkers", String.class);
        method.setAccessible(true);
        // 第3步：确认正文保留且来源标记被移除
        String cleaned = (String) method.invoke(service, "结论是按制度执行。[来源: 员工制度.pdf, 第3段]");
        assertEquals("结论是按制度执行。", cleaned);
    }
}
