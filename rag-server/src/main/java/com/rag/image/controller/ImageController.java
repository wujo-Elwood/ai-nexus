package com.rag.image.controller;

import com.rag.image.dto.ImageGenerateRequest;
import com.rag.image.entity.ImageHistory;
import com.rag.image.service.ImageGenerateService;
import com.rag.image.vo.ImageTaskResponse;
import com.rag.utils.JwtUtils;
import com.rag.vo.Result;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * AI 生图控制器
 * 提供同步生图、历史查看、图片下载和删除接口
 */
@RestController
@RequestMapping("/api/image")
public class ImageController {

    private final ImageGenerateService imageGenerateService;
    private final JwtUtils jwtUtils;

    /**
     * 创建 AI 生图控制器
     */
    public ImageController(ImageGenerateService imageGenerateService, JwtUtils jwtUtils) {
        this.imageGenerateService = imageGenerateService;
        this.jwtUtils = jwtUtils;
    }

    /**
     * 同步生成图片
     */
    @PostMapping("/generate")
    public Result<ImageTaskResponse> generate(@Valid @RequestBody ImageGenerateRequest request, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(imageGenerateService.createTask(request, userId));
    }

    /**
     * 查询当前用户的生图任务
     */
    @GetMapping("/tasks")
    public Result<List<ImageTaskResponse>> tasks(@RequestParam(value = "limit", required = false) Integer limit,
                                                 HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(imageGenerateService.listTasks(userId, limit));
    }

    /**
     * 查询单个生图任务详情
     */
    @GetMapping("/tasks/{id}")
    public Result<ImageTaskResponse> task(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(imageGenerateService.getTask(id, userId));
    }

    /**
     * 查询当前用户的生图历史
     */
    @GetMapping("/history")
    public Result<List<ImageHistory>> history(@RequestParam(value = "limit", required = false) Integer limit,
                                              HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(imageGenerateService.listHistory(userId, limit));
    }

    /**
     * 查看历史图片
     */
    @GetMapping("/history/{id}/view")
    public void view(@PathVariable Long id,
                     @RequestParam(value = "token", required = false) String token,
                     HttpServletRequest request,
                     HttpServletResponse response) throws Exception {
        Long userId = resolveUserId(request, token);
        ImageHistory history = imageGenerateService.getHistory(id, userId);
        writeImageResponse(history, response, false);
    }

    /**
     * 下载历史图片
     */
    @GetMapping("/history/{id}/download")
    public void download(@PathVariable Long id,
                         @RequestParam(value = "token", required = false) String token,
                         HttpServletRequest request,
                         HttpServletResponse response) throws Exception {
        Long userId = resolveUserId(request, token);
        ImageHistory history = imageGenerateService.getHistory(id, userId);
        writeImageResponse(history, response, true);
    }

    /**
     * 删除历史图片
     */
    @DeleteMapping("/history/{id}")
    public Result<Void> deleteHistory(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        imageGenerateService.deleteHistory(id, userId);
        return Result.success();
    }

    /**
     * 解析图片访问用户
     */
    private Long resolveUserId(HttpServletRequest request, String token) {
        // 第1步：优先使用拦截器写入的用户编号
        Object userId = request.getAttribute("userId");
        if (userId instanceof Long value) {
            return value;
        }
        // 第2步：新标签页图片访问没有请求头时，使用 URL token
        if (token != null && !token.isBlank() && jwtUtils.validateToken(token)) {
            return jwtUtils.getUserId(token);
        }
        throw new com.rag.common.BusinessException(401, "请先登录后查看图片");
    }

    /**
     * 写出图片响应
     */
    private void writeImageResponse(ImageHistory history, HttpServletResponse response, boolean download) throws Exception {
        // 第1步：设置响应头
        response.setContentType(history.getMimeType());
        if (download) {
            String fileName = URLEncoder.encode(history.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName);
        }
        // 第2步：输出图片文件
        java.nio.file.Path imagePath = Paths.get(history.getFilePath());
        if (!Files.exists(imagePath)) {
            throw new com.rag.common.BusinessException(404, "图片文件不存在");
        }
        Files.copy(imagePath, response.getOutputStream());
    }
}
