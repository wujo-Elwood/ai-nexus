package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.ai.EmbeddingService;
import com.rag.entity.KbChunk;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.ChunkMapper;
import com.rag.rag.KeywordSearchService;
import com.rag.rag.QdrantService;
import com.rag.rag.RetrievalCandidate;
import com.rag.rag.RetrievalSelector;
import com.rag.service.KnowledgeBaseService;
import com.rag.skill.core.AgentTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库检索技能：包装平台 RAG 检索管线（向量 + 关键词混合召回）
 * 让智能体能查询当前用户可见的知识库并拿到带来源的片段；
 * 默认检索全部可见知识库，也可用 kbId 聚焦单个知识库；
 * 向量通道（Qdrant）不可用时自动降级为仅关键词检索
 */
@Component
public class KnowledgeSearchTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int MAX_KB_COUNT = 5;
    private static final int SNIPPET_MAX_LENGTH = 400;
    /** 关键词命中的基础向量分，与聊天检索管线保持一致 */
    private static final float KEYWORD_BASE_SCORE = 0.3f;

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    private final KeywordSearchService keywordSearchService;
    private final ChunkMapper chunkMapper;
    private final RetrievalSelector retrievalSelector = new RetrievalSelector();

    public KnowledgeSearchTool(KnowledgeBaseService knowledgeBaseService,
                               EmbeddingService embeddingService,
                               QdrantService qdrantService,
                               KeywordSearchService keywordSearchService,
                               ChunkMapper chunkMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
        this.keywordSearchService = keywordSearchService;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "检索当前用户可见的知识库内容，返回带来源的相关片段。"
                + "回答产品资料、制度文档、合同内容等知识库相关问题前必须调用本技能，基于检索结果回答，不要凭空编造。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("query", "string", "检索问题或关键词，尽量使用与知识库文档一致的表述", true),
                new ToolParam("kbId", "number", "知识库编号；不传则在用户全部可见知识库（最多 " + MAX_KB_COUNT + " 个）中检索", false),
                new ToolParam("topK", "number", "返回片段数 1-" + MAX_TOP_K + "，默认 " + DEFAULT_TOP_K, false));
    }

    @Override
    public String execute(JsonNode arguments) {
        // 第1步：读取框架注入的用户身份与模型给出的参数
        long userId = arguments.path("userId").asLong(0);
        if (userId <= 0) {
            return "未获取到用户身份，无法检索知识库";
        }
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return "缺少检索词 query，无法检索知识库";
        }
        int topK = parseTopK(arguments.path("topK"));

        // 第2步：确定检索范围（指定知识库需校验访问权限）
        List<KnowledgeBase> targets = resolveKbScope(arguments.path("kbId"), userId);
        if (targets == null) {
            return "无法访问编号 " + arguments.path("kbId").asLong() + " 的知识库：权限不足或不存在";
        }
        if (targets.isEmpty()) {
            return "当前没有可访问的知识库，请告知用户先创建知识库并上传文件后再试";
        }

        // 第3步：查询向量；Embedding 服务不可用时降级为仅关键词检索
        float[] embedding = null;
        try {
            embedding = embeddingService.embed(query);
        } catch (Exception e) {
            // 向量通道失败，下面按仅关键词检索处理
        }

        // 第4步：逐知识库混合召回
        Map<Long, String> chunkKbNames = new HashMap<>();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (KnowledgeBase kb : targets) {
            candidates.addAll(searchKb(kb, query, embedding, topK * 2, chunkKbNames));
        }

        // 第5步：统一排序选择并格式化返回
        List<RetrievalCandidate> selected = retrievalSelector.select(candidates, topK);
        if (selected.isEmpty()) {
            return "知识库中没有检索到与「" + query + "」相关的内容，请如实告知用户，不要编造答案";
        }
        return buildResult(selected, chunkKbNames);
    }

    /** 解析检索范围：指定 kbId 时校验权限（无权限返回 null），否则取用户可见知识库（限量） */
    private List<KnowledgeBase> resolveKbScope(JsonNode kbIdNode, long userId) {
        if (kbIdNode.canConvertToLong() && kbIdNode.asLong() > 0) {
            Long kbId = kbIdNode.asLong();
            try {
                knowledgeBaseService.checkAccess(kbId, userId);
                return List.of(knowledgeBaseService.getById(kbId));
            } catch (Exception e) {
                return null;
            }
        }
        List<KnowledgeBase> visible = knowledgeBaseService.getVisible(userId);
        return visible.size() > MAX_KB_COUNT ? visible.subList(0, MAX_KB_COUNT) : visible;
    }

    /** 单知识库混合召回：向量 + 关键词，通道失败各自降级 */
    private List<RetrievalCandidate> searchKb(KnowledgeBase kb, String query, float[] embedding,
                                              int candidateK, Map<Long, String> chunkKbNames) {
        List<KbChunk> merged = new ArrayList<>();
        Set<Long> mergedIds = new HashSet<>();
        Map<Long, Float> vectorScores = new HashMap<>();
        Set<Long> keywordIds = new HashSet<>();
        // 全文索引返回的原始相关度，用于把关键词分数细化为真实强弱
        Map<Long, Double> keywordRelevance = new HashMap<>();

        // 向量召回：Qdrant 不可用时跳过
        if (embedding != null) {
            try {
                for (QdrantService.SearchResult result : qdrantService.searchWithScore(embedding, candidateK, kb.getId())) {
                    KbChunk chunk = chunkMapper.findByIdAndKbId(result.id, kb.getId());
                    if (chunk != null && mergedIds.add(chunk.getId())) {
                        merged.add(chunk);
                        vectorScores.put(chunk.getId(), result.score);
                    }
                }
            } catch (Exception e) {
                // 向量通道失败，保留关键词通道
            }
        }

        // 关键词召回
        try {
            for (KbChunk chunk : keywordSearchService.search(query, kb.getId(), candidateK)) {
                if (chunk == null) {
                    continue;
                }
                keywordRelevance.merge(chunk.getId(),
                        chunk.getRelevance() == null ? 0d : chunk.getRelevance(), Math::max);
                if (mergedIds.contains(chunk.getId())) {
                    keywordIds.add(chunk.getId());
                } else if (mergedIds.add(chunk.getId())) {
                    merged.add(chunk);
                    keywordIds.add(chunk.getId());
                }
            }
        } catch (Exception e) {
            // 关键词通道失败则本知识库无结果
        }

        double maxKeywordRelevance = keywordRelevance.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0d);

        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (KbChunk chunk : merged) {
            RetrievalCandidate candidate = new RetrievalCandidate(chunk);
            boolean keywordMatched = keywordIds.contains(chunk.getId());
            boolean vectorMatched = vectorScores.containsKey(chunk.getId());
            candidate.setVectorScore(vectorMatched ? vectorScores.get(chunk.getId()) : KEYWORD_BASE_SCORE);
            candidate.setKeywordScore(RetrievalCandidate.normalizeKeywordScore(
                    keywordMatched, keywordRelevance.get(chunk.getId()), maxKeywordRelevance));
            candidate.setMatchType(vectorMatched && keywordMatched ? "HYBRID"
                    : keywordMatched ? "KEYWORD" : "VECTOR");
            candidates.add(candidate);
            chunkKbNames.put(chunk.getId(), kb.getName());
        }
        return candidates;
    }

    /** 格式化检索结果：编号 + 来源 + 匹配方式 + 片段内容 */
    private String buildResult(List<RetrievalCandidate> selected, Map<Long, String> chunkKbNames) {
        StringBuilder builder = new StringBuilder("已在知识库中检索到 ")
                .append(selected.size())
                .append(" 个相关片段，请基于这些内容回答用户问题，并说明信息来自哪个知识库：\n");
        int index = 1;
        for (RetrievalCandidate candidate : selected) {
            KbChunk chunk = candidate.getChunk();
            builder.append("\n[").append(index++).append("] 来源：")
                    .append(chunkKbNames.getOrDefault(chunk.getId(), "知识库"))
                    .append(" / ").append(chunk.getFileName() == null ? "未知文件" : chunk.getFileName())
                    .append("（").append(candidate.getMatchType() == null ? "VECTOR" : candidate.getMatchType()).append("）\n")
                    .append(snippet(chunk.getContent())).append('\n');
        }
        return builder.toString();
    }

    /** 片段内容截断：去掉换行并限制长度 */
    private String snippet(String content) {
        String normalized = content == null ? "" : content.replace("\r", " ").replace("\n", " ").trim();
        return normalized.length() <= SNIPPET_MAX_LENGTH ? normalized : normalized.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }

    /** 解析 topK 参数并限制范围 */
    private int parseTopK(JsonNode topKNode) {
        int topK = topKNode.canConvertToInt() ? topKNode.asInt(DEFAULT_TOP_K) : DEFAULT_TOP_K;
        return Math.max(1, Math.min(topK, MAX_TOP_K));
    }
}
