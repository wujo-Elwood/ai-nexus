package com.rag.extract.controller;

import com.rag.extract.dto.CreateExtractTaskRequest;
import com.rag.extract.dto.ExportExtractTaskRequest;
import com.rag.extract.dto.UpdateExtractResultRequest;
import com.rag.extract.entity.ExtractDocument;
import com.rag.extract.entity.ExtractResult;
import com.rag.extract.entity.ExtractTask;
import com.rag.extract.service.ExtractService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档抽取控制器
 */
@RestController
@RequestMapping("/api/extract")
public class ExtractController {

    /**
     * 文档抽取服务
     */
    @Autowired
    private ExtractService extractService;

    /**
     * 查询可用抽取模板
     */
    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> getTemplates() {
        // 第1步：调用服务查询模板列表
        List<Map<String, Object>> templates = extractService.getTemplates();
        // 第2步：返回统一成功响应
        return Result.success(templates);
    }

    /**
     * 上传待抽取文档
     */
    @PostMapping("/documents/upload")
    public Result<ExtractDocument> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务保存上传文档
        ExtractDocument document = extractService.uploadDocument(file, userId);
        // 第3步：返回统一成功响应
        return Result.success(document);
    }

    /**
     * 创建文档抽取任务
     */
    @PostMapping("/tasks")
    public Result<ExtractTask> createTask(@RequestBody CreateExtractTaskRequest body, HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务创建并执行抽取任务
        ExtractTask task = extractService.createTask(body.getDocumentId(), body.getTemplateId(), userId);
        // 第3步：返回统一成功响应
        return Result.success(task);
    }

    /**
     * 查询当前用户抽取任务列表
     */
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> listTasks(HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务查询任务列表
        List<Map<String, Object>> tasks = extractService.listTasks(userId);
        // 第3步：返回统一成功响应
        return Result.success(tasks);
    }

    /**
     * 查询抽取任务详情
     */
    @GetMapping("/tasks/{taskId}")
    public Result<ExtractTask> getTask(@PathVariable Long taskId, HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务查询任务详情
        ExtractTask task = extractService.getTask(taskId, userId);
        // 第3步：返回统一成功响应
        return Result.success(task);
    }

    /**
     * 查询抽取任务结果
     */
    @GetMapping("/tasks/{taskId}/results")
    public Result<List<ExtractResult>> getResults(@PathVariable Long taskId, HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务查询任务结果
        List<ExtractResult> results = extractService.getTaskResults(taskId, userId);
        // 第3步：返回统一成功响应
        return Result.success(results);
    }

    /**
     * 更新抽取结果人工值
     */
    @PutMapping("/results/{resultId}")
    public Result<Void> updateResult(@PathVariable Long resultId, @RequestBody UpdateExtractResultRequest body, HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务更新抽取结果
        extractService.updateResult(resultId, body.getNewValue(), body.getRemark(), userId);
        // 第3步：返回统一成功响应
        return Result.success();
    }

    /**
     * 导出抽取任务结果
     */
    @PostMapping("/tasks/{taskId}/export")
    public ResponseEntity<?> exportTask(@PathVariable Long taskId, @RequestBody ExportExtractTaskRequest body, HttpServletRequest request) {
        // 第1步：从请求中读取当前用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：调用服务生成导出响应
        return extractService.exportTask(taskId, body.getExportType(), userId);
    }
}
