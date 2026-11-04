package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.image.dto.ImageGenerateRequest;
import com.rag.image.service.ImageGenerateService;
import com.rag.image.vo.GeneratedImageItem;
import com.rag.image.vo.ImageGenerateResponse;
import com.rag.image.vo.ImageTaskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生图工具测试：验证用户上下文校验、任务轮询和结果渲染（不依赖真实模型供应商）
 */
class AgentToolImageTest {

    /** 构造只覆盖任务提交与查询的桩服务，不触发真实生图 */
    private ImageGenerateService newStubService(ImageTaskResponse stubTask) {
        return new ImageGenerateService(null, null, new ObjectMapper(), "tmp") {
            @Override
            public ImageTaskResponse createTask(ImageGenerateRequest request, Long userId) {
                return stubTask;
            }

            @Override
            public ImageTaskResponse getTask(Long taskId, Long userId) {
                return stubTask;
            }
        };
    }

    private ImageTaskResponse task(String status, String errorMessage, ImageGenerateResponse result) {
        return ImageTaskResponse.builder()
                .id(9L)
                .taskStatus(status)
                .errorMessage(errorMessage)
                .result(result)
                .build();
    }

    @Test
    void missingUserIdReturnsHint() throws Exception {
        GenerateImageTool tool = new GenerateImageTool(newStubService(null));
        String result = tool.execute(new ObjectMapper().readTree("{\"prompt\":\"一只猫\"}"));
        assertTrue(result.contains("未获取到用户身份"));
    }

    @Test
    void missingPromptReturnsHint() throws Exception {
        GenerateImageTool tool = new GenerateImageTool(newStubService(null));
        String result = tool.execute(new ObjectMapper().readTree("{\"userId\":1}"));
        assertTrue(result.contains("缺少提示词"));
    }

    @Test
    void successTaskReturnsHistoryIds() throws Exception {
        ImageGenerateResponse result = ImageGenerateResponse.builder()
                .providerName("测试供应商")
                .modelName("test-model")
                .size("1024x1024")
                .images(List.of(
                        GeneratedImageItem.builder().historyId(5L).build(),
                        GeneratedImageItem.builder().historyId(6L).build()))
                .build();
        GenerateImageTool tool = new GenerateImageTool(newStubService(task("SUCCESS", null, result)));
        String out = tool.execute(new ObjectMapper().readTree("{\"userId\":1,\"prompt\":\"一只猫\",\"n\":2}"));
        assertTrue(out.contains("图片生成成功"));
        assertTrue(out.contains("5、6"));
        assertTrue(out.contains("「AI 生图」"));
        // 结果只给历史编号，不允许出现需要登录态的图片链接
        assertFalse(out.contains("http"));
    }

    @Test
    void failedTaskReturnsReason() throws Exception {
        GenerateImageTool tool = new GenerateImageTool(newStubService(task("FAILED", "供应商余额不足", null)));
        String out = tool.execute(new ObjectMapper().readTree("{\"userId\":1,\"prompt\":\"一只猫\"}"));
        assertTrue(out.contains("生图任务失败"));
        assertTrue(out.contains("供应商余额不足"));
    }
}
