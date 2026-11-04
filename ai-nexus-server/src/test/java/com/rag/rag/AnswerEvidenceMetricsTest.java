package com.rag.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 回答置信度和证据覆盖率测试。 */
class AnswerEvidenceMetricsTest {

    /** 有效引用回答应返回高于无证据回答的置信度和覆盖率。 */
    @Test
    void groundedAnswerShouldExposeConfidenceAndEvidenceCoverage() {
        KnowledgeCitation citation = new KnowledgeCitation();
        citation.setFinalScore(0.9f);
        Map<String, Object> metrics = AnswerEvidenceMetrics.calculate(
                "员工年假为十天。[来源: 员工制度.pdf, 第3段]", true, List.of(citation));

        assertEquals(1, metrics.get("citationCount"));
        assertEquals(100, metrics.get("evidenceCoverage"));
        assertEquals("HIGH", metrics.get("confidenceLevel"));
    }

    /** 无证据回答的指标必须归零。 */
    @Test
    void ungroundedAnswerShouldReturnZeroMetrics() {
        Map<String, Object> metrics = AnswerEvidenceMetrics.calculate("无法回答", false, List.of());

        assertEquals(0, metrics.get("confidence"));
        assertEquals(0, metrics.get("evidenceCoverage"));
        assertEquals("NONE", metrics.get("confidenceLevel"));
    }

    /** 同一句包含多个引用时，覆盖率仍按句子数量计算。 */
    @Test
    void multipleCitationsInOneSentenceShouldNotOverstateCoverage() {
        KnowledgeCitation first = new KnowledgeCitation();
        first.setFinalScore(0.8f);
        KnowledgeCitation second = new KnowledgeCitation();
        second.setFinalScore(0.8f);

        Map<String, Object> metrics = AnswerEvidenceMetrics.calculate(
                "员工年假为十天。[来源: 制度一.pdf, 第3段][来源: 制度二.pdf, 第2段]。另请参考考勤规定。",
                true, List.of(first, second));

        assertEquals(50, metrics.get("evidenceCoverage"));
    }
}
