package com.rag.controller;

import com.rag.kb.KnowledgeSummaryService;
import com.rag.service.FileService;
import com.rag.service.KnowledgeBaseService;
import com.rag.rag.QdrantService;
import com.rag.vo.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

/** 知识库控制器测试：仅覆盖本次新增的重建向量接口和删除联动清理行为。 */
class KnowledgeBaseControllerTest {

    private KnowledgeBaseController buildController() {
        KnowledgeBaseController controller = new KnowledgeBaseController();
        ReflectionTestUtils.setField(controller, "knowledgeBaseService", mock(KnowledgeBaseService.class));
        ReflectionTestUtils.setField(controller, "knowledgeSummaryService", mock(KnowledgeSummaryService.class));
        ReflectionTestUtils.setField(controller, "fileService", mock(FileService.class));
        return controller;
    }

    private MockHttpServletRequest requestWithUser(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId);
        return request;
    }

    /** 重建向量接口应委托文件服务并返回提交重建的文件数量。 */
    @Test
    void rebuildVectorsShouldReturnSubmittedFileCount() {
        FileService fileService = mock(FileService.class);
        when(fileService.reprocessKb(8L, 3L)).thenReturn(5);
        KnowledgeBaseController controller = new KnowledgeBaseController();
        ReflectionTestUtils.setField(controller, "knowledgeBaseService", mock(KnowledgeBaseService.class));
        ReflectionTestUtils.setField(controller, "knowledgeSummaryService", mock(KnowledgeSummaryService.class));
        ReflectionTestUtils.setField(controller, "fileService", fileService);

        Result<Integer> result = controller.rebuildVectors(8L, requestWithUser(3L));

        assertEquals(5, result.getData());
        verify(fileService).reprocessKb(8L, 3L);
    }

    /** 删除知识库应委托服务层执行关联数据清理和删除。 */
    @Test
    void deleteShouldDelegateToServiceWithCleanup() {
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        KnowledgeBaseController controller = buildController();
        ReflectionTestUtils.setField(controller, "knowledgeBaseService", kbService);

        controller.delete(8L, requestWithUser(3L));

        verify(kbService).delete(8L, 3L);
    }

    /**
     * 全局向量重建接口应委托文件服务并返回提交数量
     */
    @Test
    void rebuildAllVectorsShouldDelegateToFileService() {
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        FileService fileService = mock(FileService.class);
        when(fileService.rebuildAllVectors(3L)).thenReturn(7);
        KnowledgeBaseController controller = buildController();
        ReflectionTestUtils.setField(controller, "knowledgeBaseService", kbService);
        ReflectionTestUtils.setField(controller, "fileService", fileService);

        Result<Integer> result = controller.rebuildAllVectors(requestWithUser(3L));

        assertEquals(7, result.getData());
        verify(fileService).rebuildAllVectors(3L);
    }

    /**
     * 全局向量重建状态接口应返回文件服务维护的进度快照
     */
    @Test
    void rebuildVectorsStatusShouldReturnProgressSnapshot() {
        FileService fileService = mock(FileService.class);
        Map<String, Object> snapshot = Map.of(
                "rebuilding", true,
                "totalFiles", 10,
                "completedFiles", 4,
                "failedFiles", 1,
                "progress", 50);
        when(fileService.getRebuildStatus()).thenReturn(snapshot);
        KnowledgeBaseController controller = buildController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);

        Result<Map<String, Object>> result = controller.rebuildVectorsStatus();

        assertEquals(snapshot, result.getData());
        verify(fileService).getRebuildStatus();
    }
}
