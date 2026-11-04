package com.rag.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索候选选择器
 * 负责低分过滤、向量与关键词融合以及相似片段去重
 */
@Slf4j
@Component
public class RetrievalSelector {

    /** 向量最低相似度 */
    @Value("${rag.similarity-threshold:0.25}")
    private float similarityThreshold = 0.25f;

    /** 向量分数权重 */
    @Value("${rag.vector-weight:0.6}")
    private float vectorWeight = 0.6f;

    /** 关键词分数权重 */
    @Value("${rag.keyword-weight:0.4}")
    private float keywordWeight = 0.4f;

    /** 相似文本去重阈值 */
    @Value("${rag.diversity-threshold:0.85}")
    private float diversityThreshold = 0.85f;

    /**
     * 选择最终进入上下文的候选
     *
     * @param candidates 候选列表
     * @param topK 最终数量
     * @return 按融合分数排序且已去重的候选
     */
    public List<RetrievalCandidate> select(List<RetrievalCandidate> candidates, int topK) {
        // 调用可覆盖参数的选择方法并使用系统默认配置
        return select(candidates, topK, similarityThreshold, vectorWeight, keywordWeight, diversityThreshold);
    }

    /** 按本次调试或知识库策略选择最终候选 */
    public List<RetrievalCandidate> select(List<RetrievalCandidate> candidates, int topK,
                                           float threshold, float vectorRatio, float keywordRatio,
                                           float diversity) {
        List<RetrievalCandidate> eligible = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            float vectorScore = candidate.getVectorScore();
            float keywordScore = candidate.getKeywordScore();
            candidate.setFinalScore(vectorRatio * vectorScore + keywordRatio * keywordScore);
            boolean keywordMatched = keywordScore > 0 || "KEYWORD".equals(candidate.getMatchType())
                    || "HYBRID".equals(candidate.getMatchType());
            if (!keywordMatched && vectorScore < threshold) {
                candidate.setFilterReason("BELOW_THRESHOLD");
                continue;
            }
            eligible.add(candidate);
        }
        eligible.sort(Comparator.comparing(RetrievalCandidate::getFinalScore).reversed());

        List<RetrievalCandidate> selected = new ArrayList<>();
        for (RetrievalCandidate candidate : eligible) {
            boolean duplicate = false;
            for (RetrievalCandidate existing : selected) {
                if (contentSimilarity(candidate.getChunk().getContent(), existing.getChunk().getContent()) >= diversity) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                candidate.setFilterReason("DUPLICATE_CONTENT");
                continue;
            }
            candidate.setSelected(true);
            selected.add(candidate);
            if (selected.size() >= topK) {
                break;
            }
        }
        // 标记超过 Top-K 但通过质量过滤的候选，便于调试台解释未入选原因
        for (RetrievalCandidate candidate : eligible) {
            if (!candidate.isSelected() && candidate.getFilterReason() == null) {
                candidate.setFilterReason("OUTSIDE_TOP_K");
            }
        }
        return selected;
    }

    /**
     * 计算两个文本的词集合 Jaccard 相似度
     */
    private float contentSimilarity(String first, String second) {
        Set<String> firstTerms = extractTerms(first);
        Set<String> secondTerms = extractTerms(second);
        if (firstTerms.isEmpty() || secondTerms.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(firstTerms);
        intersection.retainAll(secondTerms);
        Set<String> union = new HashSet<>(firstTerms);
        union.addAll(secondTerms);
        return (float) intersection.size() / union.size();
    }

    /**
     * 提取英文词和连续中文片段
     */
    private Set<String> extractTerms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        for (String token : text.split("[\\s,;.!?，。；！？、\\-]+")) {
            if (token.length() >= 2) {
                terms.add(token.toLowerCase(Locale.ROOT));
            }
        }
        for (int length = 2; length <= 4; length++) {
            for (int index = 0; index + length <= text.length(); index++) {
                String gram = text.substring(index, index + length);
                if (gram.matches("[\\u4e00-\\u9fa5]+")) {
                    terms.add(gram);
                }
            }
        }
        return terms;
    }
}
