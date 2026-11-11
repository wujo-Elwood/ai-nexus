package com.rag.kb;

import com.rag.entity.KnowledgeBase;
import com.rag.controller.KnowledgeBaseController;
import com.rag.vo.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库摘要控制器测试。 */
class KnowledgeSummaryControllerTest {

    /** 查询摘要时应传递知识库和当前用户编号。 */
    @Test
    void getSummaryShouldUseAuthenticatedUserId() {
        KnowledgeSummaryService service = mock(KnowledgeSummaryService.class);
        KnowledgeBaseController controller = new KnowledgeBaseController();
        ReflectionTestUtils.setField(controller, "knowledgeSummaryService", service);
        KnowledgeBase summary = new KnowledgeBase();
        when(service.getSummary(8L, 3L)).thenReturn(summary);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 3L);

        Result<KnowledgeBase> result = controller.getSummary(8L, request);

        assertEquals(summary, result.getData());
        verify(service).getSummary(8L, 3L);
    }

    /** 生成摘要时应调用生成服务并返回最新知识库。 */
    @Test
    void generateSummaryShouldReturnUpdatedKnowledgeBase() {
        KnowledgeSummaryService service = mock(KnowledgeSummaryService.class);
        KnowledgeBase updated = new KnowledgeBase();
        when(service.generateSummary(8L, 3L)).thenReturn(updated);
        KnowledgeBaseController controller = new KnowledgeBaseController();
        ReflectionTestUtils.setField(controller, "knowledgeSummaryService", service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 3L);

        Result<KnowledgeBase> result = controller.generateSummary(8L, request);

        assertEquals(updated, result.getData());
        verify(service).generateSummary(8L, 3L);
    }
}
