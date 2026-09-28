package com.rag.mapper;

import com.rag.entity.KnowledgeGapReport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识缺口报告 Mapper 测试。 */
class KnowledgeGapReportMapperTest {

    /** 报告实体应携带窗口、统计、状态和快照字段。 */
    @Test
    void reportShouldExposeSnapshotFields() {
        KnowledgeGapReport report = new KnowledgeGapReport();
        report.setWindowDays(7);
        report.setSampleCount(3);
        report.setStatus("COMPLETED");
        report.setReportJson("{\"topics\":[]}");

        assertEquals(7, report.getWindowDays());
        assertEquals(3, report.getSampleCount());
        assertEquals("COMPLETED", report.getStatus());
    }

    /** 失败更新只能修改状态和错误，不得覆盖最近一次成功报告。 */
    @Test
    void failureUpdateShouldNotOverwriteReportJson() throws Exception {
        // 读取 MyBatis 映射文件，验证失败更新 SQL 未写入 report_json
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/KnowledgeGapReportMapper.xml")) {
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int statementStart = mapperXml.indexOf("<update id=\"updateFailure\"");
            int statementEnd = mapperXml.indexOf("</update>", statementStart);
            String statement = mapperXml.substring(statementStart, statementEnd);

            assertTrue(statement.contains("status = #{status}"));
            assertTrue(statement.contains("error_message = #{errorMessage}"));
            assertTrue(statement.contains("generated_at = #{generatedAt}"));
            assertTrue(!statement.contains("report_json"));
        }
    }
}
