package com.rag.knowledgegap;

import com.rag.common.BusinessException;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 知识缺口分析控制器测试。 */
class KnowledgeGapControllerTest {

    /** 读取报告应将窗口参数交给分析服务。 */
    @Test
    void reportShouldReturnSelectedWindow() {
        KnowledgeGapAnalysisService service = mock(KnowledgeGapAnalysisService.class);
        when(service.getReport(7)).thenReturn(Map.of("windowDays", 7));
        KnowledgeGapController controller = new KnowledgeGapController(service);

        Result<Map<String, Object>> result = controller.report(7);

        assertEquals(200, result.getCode());
        assertEquals(7, result.getData().get("windowDays"));
    }

    /** 非法窗口由服务统一拒绝。 */
    @Test
    void reportShouldRejectUnsupportedWindow() {
        KnowledgeGapAnalysisService service = mock(KnowledgeGapAnalysisService.class);
        when(service.getReport(14)).thenThrow(new BusinessException(400, "分析窗口只支持最近 7 天或最近 30 天"));
        KnowledgeGapController controller = new KnowledgeGapController(service);

        assertThrows(BusinessException.class, () -> controller.report(14));
    }

    /** 管理员触发分析时应传递 JWT 中的用户编号。 */
    @Test
    void analyzeShouldPassCurrentUserId() {
        KnowledgeGapAnalysisService service = mock(KnowledgeGapAnalysisService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("userId")).thenReturn(9L);
        when(service.triggerAnalysis(30, 9L)).thenReturn(Map.of("days", 30, "status", "RUNNING"));
        KnowledgeGapController controller = new KnowledgeGapController(service);

        Result<Map<String, Object>> result = controller.analyze(30, request);

        assertEquals("RUNNING", result.getData().get("status"));
    }
}
