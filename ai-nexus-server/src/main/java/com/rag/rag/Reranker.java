package com.rag.rag;

import com.rag.entity.KbChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序服务
 * 对向量检索的召回结果做二次排序，融合向量相似度和关键词匹配分数
 * 不依赖外部重排模型，使用轻量级 BM25-like 关键词评分
 */
@Slf4j
@Component
public class Reranker {

    /** 向量分数权重 */
    private static final double VECTOR_WEIGHT = 0.6;
    /** 关键词分数权重 */
    private static final double KEYWORD_WEIGHT = 0.4;

    /**
     * 对召回的 chunk 列表做重排序
     *
     * @param query       用户查询文本
     * @param chunks      召回的 chunk 列表
     * @param vectorScore 每个 chunk 对应的向量相似度分数（与 chunks 顺序一致）
     * @param topK        最终返回的数量
     * @return 重排序后的 chunk 列表
     */
    public List<KbChunk> rerank(String query, List<KbChunk> chunks,
                                 List<Float> vectorScore, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        // 第1步：提取查询关键词（按空格和标点分词）
        Set<String> queryTerms = extractTerms(query);
        if (queryTerms.isEmpty()) {
            // 没有有效关键词时直接按向量分数排序
            return sortByVectorScore(chunks, vectorScore, topK);
        }

        // 第2步：计算每个 chunk 的融合分数
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float vScore = (i < vectorScore.size()) ? vectorScore.get(i) : 0f;
            double kScore = computeKeywordScore(queryTerms, chunks.get(i).getContent());
            double finalScore = VECTOR_WEIGHT * vScore + KEYWORD_WEIGHT * kScore;
            scored.add(new ScoredChunk(chunks.get(i), finalScore));
        }

        // 第3步：按融合分数降序排序，取 topK
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
    }

    /**
     * 计算关键词匹配分数（BM25-like）
     * 统计查询词在文档中的出现频率，归一化到 0-1
     */
    private double computeKeywordScore(Set<String> queryTerms, String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        // 第1步：把文档内容也拆成词集合
        Set<String> docTerms = extractTerms(content);
        if (docTerms.isEmpty()) {
            return 0;
        }
        // 第2步：计算命中词数占查询词总数的比例
        long matchCount = queryTerms.stream().filter(docTerms::contains).count();
        return (double) matchCount / queryTerms.size();
    }

    /**
     * 提取文本中的关键词（简单的中文/英文分词）
     * 按空格、标点分割，过滤掉过短的词
     */
    private Set<String> extractTerms(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }
        // 第1步：按常见分隔符拆分
        String[] tokens = text.split("[\\s,;.!?，。；！？、\\-]+");
        Set<String> terms = new HashSet<>();
        for (String token : tokens) {
            // 第2步：过滤太短的词（单字无意义）
            if (token.length() >= 2) {
                terms.add(token.toLowerCase());
            }
        }
        // 第3步：也把连续2-4个字作为中文分词片段
        for (int len = 2; len <= 4; len++) {
            for (int i = 0; i <= text.length() - len; i++) {
                String gram = text.substring(i, i + len).toLowerCase();
                if (gram.matches("[\\u4e00-\\u9fa5]+")) {
                    terms.add(gram);
                }
            }
        }
        return terms;
    }

    /**
     * 纯按向量分数排序（没有关键词时的降级方案）
     */
    private List<KbChunk> sortByVectorScore(List<KbChunk> chunks, List<Float> scores, int topK) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float s = (i < scores.size()) ? scores.get(i) : 0f;
            scored.add(new ScoredChunk(chunks.get(i), s));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());
    }

    /** 内部数据结构：chunk + 分数 */
    private record ScoredChunk(KbChunk chunk, double score) {}
}
