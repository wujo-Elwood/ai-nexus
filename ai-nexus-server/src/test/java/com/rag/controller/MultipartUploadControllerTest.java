package com.rag.controller;

import com.rag.dto.MultipartUploadInitRequest;
import com.rag.dto.MultipartUploadInitResponse;
import com.rag.service.MultipartUploadService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分片上传控制器测试
 * 验证用户身份能够传递到上传服务并保留统一响应结构
 */
class MultipartUploadControllerTest {

    /**
     * 测试初始化上传时使用当前请求用户编号
     */
    @Test
    void initShouldUseAuthenticatedUserId() {
        MultipartUploadService service = mock(MultipartUploadService.class);
        MultipartUploadController controller = new MultipartUploadController();
        ReflectionTestUtils.setField(controller, "multipartUploadService", service);
        MultipartUploadInitRequest request = new MultipartUploadInitRequest();
        request.setKbId(3L);
        request.setFileName("manual.txt");
        request.setFileSize(5L);
        when(service.init(eq(7L), any(MultipartUploadInitRequest.class)))
                .thenReturn(new MultipartUploadInitResponse());

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute("userId", 7L);
        Result<MultipartUploadInitResponse> result = controller.init(request, httpRequest);

        assertEquals(200, result.getCode());
        verify(service).init(eq(7L), eq(request));
    }
}
