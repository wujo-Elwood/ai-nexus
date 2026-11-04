package com.rag.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 计算回答置信度、证据覆盖率和引用数量。 */
public final class AnswerEvidenceMetrics {

    /** 识别回答中的来源标记。 */
    private static final Pattern SOURCE_PATTERN = Pattern.compile("\\[来源:\\s*[^\\]]+\\]");

    private AnswerEvidenceMetrics() {
    }

    /**
     * 根据可信状态、引用分数和回答中的来源标记计算可解释指标。
     *
     * @param answer 回答正文
     * @param grounded 是否通过可信回答校验
     * @param citations 本次回答使用的有效引用
     * @return 可直接序列化到 answer-meta SSE 事件的指标
     */
    public static Map<String, Object> calculate(String answer, boolean grounded,
                                                List<KnowledgeCitation> citations) {
        int citationCount = citations == null ? 0 : citations.size();
        int coverage = calculateCoverage(answer, citationCount);
        int confidence = 0;
        if (grounded && citationCount > 0) {
            double scoreTotal = 0D;
            for (KnowledgeCitation citation : citations) {
                scoreTotal += effectiveScore(citation);
            }
            double averageScore = scoreTotal / citationCount;
            confidence = (int) Math.round(clamp(averageScore, 0D, 1D) * 70D
                    + Math.min(citationCount, 5) * 3D
                    + coverage * 0.15D);
            confidence = Math.min(100, Math.max(1, confidence));
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("confidence", confidence);
        metrics.put("confidenceLevel", confidence >= 80 ? "HIGH" : confidence >= 50 ? "MEDIUM" : confidence > 0 ? "LOW" : "NONE");
        metrics.put("evidenceCoverage", coverage);
        metrics.put("citationCount", citationCount);
        return metrics;
    }

    /** 计算回答句子中实际带来源标记的句子比例。 */
    private static int calculateCoverage(String answer, int citationCount) {
        if (answer == null || answer.isBlank() || citationCount == 0) {
            return 0;
        }
        // 先将完整来源标记替换为无标点占位符，避免文件名中的句点触发错误分句。
        String normalized = SOURCE_PATTERN.matcher(answer).replaceAll("SOURCE_MARK");
        String[] sentences = normalized.trim().split(
                "(?<=[。！？])(?!SOURCE_MARK)\\s*|(?<=[.!?])(?!SOURCE_MARK)(?=\\s+|$)|(?:\\r?\\n)+");
        int sentenceCount = 0;
        int coveredSentenceCount = 0;
        for (String sentence : sentences) {
            if (!sentence.isBlank()) {
                sentenceCount++;
                if (sentence.contains("SOURCE_MARK")) {
                    coveredSentenceCount++;
                }
            }
        }
        sentenceCount = Math.max(1, sentenceCount);
        return Math.min(100, coveredSentenceCount * 100 / sentenceCount);
    }

    /** 读取引用的最终分数，兼容旧缓存引用只有向量分数的情况。 */
    private static float effectiveScore(KnowledgeCitation citation) {
        if (citation == null) {
            return 0F;
        }
        if (citation.getFinalScore() != null && citation.getFinalScore() > 0F) {
            return citation.getFinalScore();
        }
        return citation.getVectorScore() == null ? 0F : citation.getVectorScore();
    }

    /** 将浮点值限制在指定范围内。 */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
