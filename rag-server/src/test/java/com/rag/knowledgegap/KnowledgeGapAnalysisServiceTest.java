package com.rag.knowledgegap;

import com.rag.common.BusinessException;
import com.rag.entity.AnswerQuality;
import com.rag.entity.KnowledgeGapReport;
import com.rag.entity.ModelProvider;
import com.rag.mapper.AnswerQualityMapper;
import com.rag.mapper.KnowledgeGapReportMapper;
import com.rag.service.ModelProviderService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识缺口分析服务测试。 */
class KnowledgeGapAnalysisServiceTest {

    /** 空样本应返回可渲染的空主题报告，而不是空响应。 */
    @Test
    void emptySamplesShouldReturnEmptyReport() {
        AnswerQualityMapper qualityMapper = mock(AnswerQualityMapper.class);
        KnowledgeGapReportMapper reportMapper = mock(KnowledgeGapReportMapper.class);
        when(qualityMapper.findSamples(any())).thenReturn(List.of());
        KnowledgeGapAnalysisService service = new KnowledgeGapAnalysisService(qualityMapper, reportMapper,
                mock(ModelProviderService.class), Runnable::run);

        Map<String, Object> report = service.analyzeWindow(7);

        assertEquals(0, report.get("sampleCount"));
        assertTrue(((List<?>) report.get("topics")).isEmpty());
        verify(reportMapper).insert(any(KnowledgeGapReport.class));
    }

    /** LLM 返回非法 JSON 时应保留规则聚类结果。 */
    @Test
    void invalidLlmJsonShouldFallbackToRuleTopics() {
        AnswerQualityMapper qualityMapper = mock(AnswerQualityMapper.class);
        KnowledgeGapReportMapper reportMapper = mock(KnowledgeGapReportMapper.class);
        ModelProviderService providerService = mock(ModelProviderService.class);
        AnswerQuality quality = new AnswerQuality();
        quality.setQuestion("员工年假如何计算？");
        quality.setConfidence(20);
        quality.setEvidenceCoverage(0);
        quality.setRefusal(true);
        quality.setHelpful(0);
        quality.setReason("NO_EVIDENCE");
        when(qualityMapper.findSamples(any())).thenReturn(List.of(quality));
        when(providerService.getActive()).thenReturn(new ModelProvider());
        KnowledgeGapAnalysisService service = new KnowledgeGapAnalysisService(qualityMapper, reportMapper,
                providerService, Runnable::run, (prompt, provider) -> "not-json");

        Map<String, Object> report = service.analyzeWindow(7);

        assertEquals(1, ((List<?>) report.get("topics")).size());
        assertEquals("RULE_FALLBACK", report.get("analysisMethod"));
        assertTrue(String.valueOf(report.get("error")).contains("JSON"));
    }

    /** 非法窗口必须在进入数据库查询前拒绝。 */
    @Test
    void unsupportedWindowShouldBeRejected() {
        KnowledgeGapAnalysisService service = new KnowledgeGapAnalysisService(mock(AnswerQualityMapper.class),
                mock(KnowledgeGapReportMapper.class), mock(ModelProviderService.class), Runnable::run);

        assertThrows(BusinessException.class, () -> service.getReport(14));
    }

    /** 异步提交分析后应立即持久化 RUNNING 状态，供页面轮询显示真实进度。 */
    @Test
    void triggerAnalysisShouldPersistRunningStatus() {
        AnswerQualityMapper qualityMapper = mock(AnswerQualityMapper.class);
        KnowledgeGapReportMapper reportMapper = mock(KnowledgeGapReportMapper.class);
        KnowledgeGapAnalysisService service = new KnowledgeGapAnalysisService(qualityMapper, reportMapper,
                mock(ModelProviderService.class), command -> {
                    // 测试只验证提交瞬间的状态，暂不执行异步任务
                });

        Map<String, Object> result = service.triggerAnalysis(7, 9L);

        assertEquals("RUNNING", result.get("status"));
        verify(reportMapper).markRunning(eq(7), any(LocalDateTime.class));
    }
}
