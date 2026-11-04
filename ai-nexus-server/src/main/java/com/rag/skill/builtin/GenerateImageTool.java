package com.rag.skill.builtin;
import com.rag.skill.core.AgentTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.image.dto.ImageGenerateRequest;
import com.rag.image.service.ImageGenerateService;
import com.rag.image.vo.GeneratedImageItem;
import com.rag.image.vo.ImageTaskResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生图工具：包装 AI 生图模块的异步任务
 * 提交任务后轮询直到完成，让模型在对话中直接拿到生成结果并转告用户；
 * 图片本身需要登录态访问，因此结果只返回历史编号，不返回图片链接
 */
@Component
public class GenerateImageTool implements AgentTool {

    /** 单次生图最长等待时间，超过后告知任务编号让用户稍后到生图模块查看 */
    private static final long WAIT_TIMEOUT_MS = 90_000L;
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final int MAX_IMAGE_COUNT = 4;

    private final ImageGenerateService imageGenerateService;

    public GenerateImageTool(ImageGenerateService imageGenerateService) {
        this.imageGenerateService = imageGenerateService;
    }

    @Override
    public String name() {
        return "generate_image";
    }

    @Override
    public String description() {
        return "根据提示词生成 AI 图片，生成成功后保存到用户的 AI 生图历史。"
                + "适合把对话中提炼的内容（如文档要点、分析结论）转成配图。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("prompt", "string", "图片描述提示词，应具体描述画面主体、风格、构图和色调等要素", true),
                new ToolParam("size", "string", "图片尺寸，如 1024x1024，留空使用默认值", false),
                new ToolParam("n", "number", "生成张数 1-" + MAX_IMAGE_COUNT + "，留空默认 1 张", false));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        // 第1步：读取框架注入的用户身份，生图任务必须落到当前用户名下
        long userId = arguments.path("userId").asLong(0);
        if (userId <= 0) {
            return "未获取到用户身份，无法创建生图任务";
        }
        String prompt = arguments.path("prompt").asText("").trim();
        if (prompt.isEmpty()) {
            return "缺少提示词 prompt，无法生成图片";
        }

        // 第2步：组装请求并提交异步任务，参数合法性由生图服务兜底校验
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt(prompt);
        String size = arguments.path("size").asText("").trim();
        if (!size.isEmpty()) {
            request.setSize(size);
        }
        int n = arguments.path("n").asInt(1);
        if (n > 1) {
            request.setN(Math.min(n, MAX_IMAGE_COUNT));
        }
        ImageTaskResponse task = imageGenerateService.createTask(request, userId);

        // 第3步：轮询任务状态，完成后把结果回给模型
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (true) {
            ImageTaskResponse current = imageGenerateService.getTask(task.getId(), userId);
            if ("SUCCESS".equals(current.getTaskStatus())) {
                return buildSuccessResult(current);
            }
            if ("FAILED".equals(current.getTaskStatus())) {
                return "生图任务失败：" + (current.getErrorMessage() == null ? "原因未知" : current.getErrorMessage())
                        + "，请告知用户调整提示词后重试";
            }
            if (System.currentTimeMillis() >= deadline) {
                return "生图任务已提交（任务编号 " + task.getId() + "），仍在生成中，"
                        + "请告知用户稍后到「AI 生图」模块查看结果";
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "生图任务已提交（任务编号 " + task.getId() + "），等待过程被中断，"
                        + "请告知用户稍后到「AI 生图」模块查看结果";
            }
        }
    }

    /** 组装成功结果：只返回历史编号，避免模型编造需要登录态的图片链接 */
    private String buildSuccessResult(ImageTaskResponse task) {
        List<GeneratedImageItem> images = task.getResult() == null || task.getResult().getImages() == null
                ? List.of() : task.getResult().getImages();
        if (images.isEmpty()) {
            return "生图任务完成，但未返回图片信息（任务编号 " + task.getId() + "），"
                    + "请告知用户到「AI 生图」模块查看结果";
        }
        String ids = images.stream()
                .map(item -> item.getHistoryId() == null ? "无" : String.valueOf(item.getHistoryId()))
                .collect(Collectors.joining("、"));
        return "图片生成成功，共 " + images.size() + " 张（历史记录编号：" + ids + "），已保存到用户的 AI 生图历史。"
                + "请告知用户到「AI 生图」模块查看和下载图片，不要编造图片链接。";
    }
}
