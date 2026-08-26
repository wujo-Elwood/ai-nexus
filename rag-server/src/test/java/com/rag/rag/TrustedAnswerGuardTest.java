package com.rag.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可信回答校验测试
 * 验证知识库证据不足、有效引用和未知引用的处理结果
 */
class TrustedAnswerGuardTest {

    /**
     * 测试没有证据时返回固定拒答结果
     */
    @Test
    void noEvidenceShouldReturnNoEvidenceResult() {
        TrustedAnswerGuard guard = new TrustedAnswerGuard();

        TrustedAnswerResult result = guard.validate("任意回答", KnowledgeContext.empty());

        assertFalse(result.isGrounded());
        assertEquals("NO_EVIDENCE", result.getReason());
    }

    /**
     * 测试有效来源引用能够通过校验
     */
    @Test
    void validCitationShouldBeAccepted() {
        TrustedAnswerGuard guard = new TrustedAnswerGuard();
        KnowledgeCitation citation = citation("制度.pdf", 2);
        KnowledgeContext context = new KnowledgeContext("内容", "[来源: 制度.pdf, 第3段]\n内容", List.of(citation), "年假");

        TrustedAnswerResult result = guard.validate("年假按制度执行。[来源: 制度.pdf, 第3段]", context);

        assertTrue(result.isGrounded());
        assertEquals(1, result.getCitations().size());
    }

    /**
     * 测试未知文件引用不能通过校验
     */
    @Test
    void unknownCitationShouldBeRejected() {
        TrustedAnswerGuard guard = new TrustedAnswerGuard();
        KnowledgeCitation citation = citation("制度.pdf", 2);
        KnowledgeContext context = new KnowledgeContext("内容", "[来源: 制度.pdf, 第3段]\n内容", List.of(citation), "年假");

        TrustedAnswerResult result = guard.validate("答案。[来源: 未知.pdf, 第1段]", context);

        assertFalse(result.isGrounded());
        assertEquals("INVALID_CITATION", result.getReason());
    }

    /**
     * 构造引用数据
     */
    private KnowledgeCitation citation(String fileName, int chunkIndex) {
        KnowledgeCitation citation = new KnowledgeCitation();
        citation.setFileName(fileName);
        citation.setChunkIndex(chunkIndex);
        citation.setSource(fileName + ", 第" + (chunkIndex + 1) + "段");
        return citation;
    }
}
