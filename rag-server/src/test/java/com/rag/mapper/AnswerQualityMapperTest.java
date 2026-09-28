package com.rag.mapper;

import com.rag.entity.AnswerQuality;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回答质量事实 Mapper 测试。 */
class AnswerQualityMapperTest {

    /** 回答质量实体应保留分析所需的全部字段。 */
    @Test
    void answerQualityShouldExposeAnalysisFields() {
        AnswerQuality quality = new AnswerQuality();
        quality.setAnswerMessageId(12L);
        quality.setSessionId(13L);
        quality.setKbId(14L);
        quality.setQuestion("公司年假如何计算？");
        quality.setConfidence(20);
        quality.setEvidenceCoverage(0);
        quality.setRefusal(true);
        quality.setReason("NO_EVIDENCE");
        quality.setCreateTime(LocalDateTime.now());

        assertEquals(12L, quality.getAnswerMessageId());
        assertEquals(20, quality.getConfidence());
        assertTrue(quality.getRefusal());
    }

    /** 样本查询必须同时考虑质量指标和最新的无帮助反馈。 */
    @Test
    void sampleQueryShouldFilterQualitySignalsAndJoinLatestFeedback() throws Exception {
        // 读取 MyBatis 映射文件，验证实际执行的筛选条件
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/AnswerQualityMapper.xml")) {
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int statementStart = mapperXml.indexOf("<select id=\"findSamples\"");
            int statementEnd = mapperXml.indexOf("</select>", statementStart);
            String statement = mapperXml.substring(statementStart, statementEnd).replace("&lt;", "<").replace("&gt;", ">");

            assertTrue(statement.contains("create_time >= #{from}"));
            assertTrue(statement.contains("refusal = 1"));
            assertTrue(statement.contains("confidence < 50"));
            assertTrue(statement.contains("evidence_coverage < 50"));
            assertTrue(statement.contains("helpful = 0"));
            assertTrue(statement.contains("MAX(f2.id)"));
        }
    }
}
