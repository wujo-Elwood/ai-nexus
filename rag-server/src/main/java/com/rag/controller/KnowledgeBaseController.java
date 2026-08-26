package com.rag.controller;

import com.rag.entity.KnowledgeBase;
import com.rag.service.KnowledgeBaseService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库控制器
 * 提供知识库的增删查和权限管理
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    /** 创建知识库（默认私有） */
    @PostMapping
    public Result<KnowledgeBase> create(@RequestBody KnowledgeBase request, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        KnowledgeBase kb = knowledgeBaseService.create(request.getName(), request.getDescription(), userId);
        return Result.success(kb);
    }

    /** 查询用户可见的知识库（自己的全部 + 他人公开的） */
    @GetMapping
    public Result<List<KnowledgeBase>> list(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        List<KnowledgeBase> list = knowledgeBaseService.getVisible(userId);
        return Result.success(list);
    }

    /** 根据ID查询当前用户有权访问的知识库详情 */
    @GetMapping("/{id}")
    public Result<KnowledgeBase> getById(@PathVariable Long id, HttpServletRequest httpRequest) {
        // 第1步：校验当前用户是否有权访问知识库
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkAccess(id, userId);
        // 第2步：查询知识库详情
        KnowledgeBase kb = knowledgeBaseService.getById(id);
        return Result.success(kb);
    }

    /** 修改知识库名称、描述和可见范围 */
    @PutMapping("/{id}")
    public Result<KnowledgeBase> update(@PathVariable Long id,
                                        @RequestBody KnowledgeBase request,
                                        HttpServletRequest httpRequest) {
        // 第1步：读取当前登录用户编号
        Long userId = (Long) httpRequest.getAttribute("userId");
        // 第2步：修改知识库并返回最新信息
        KnowledgeBase kb = knowledgeBaseService.update(id, request.getName(), request.getDescription(),
                request.getVisibility(), userId);
        return Result.success(kb);
    }

    /** 删除知识库（只有创建者可以删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.delete(id, userId);
        return Result.success();
    }

    /** 查询知识库策略 */
    @GetMapping("/{id}/strategy")
    public Result<KnowledgeBase> getStrategy(@PathVariable Long id, HttpServletRequest request) {
        // 调用知识库服务校验读取权限并查询策略
        return Result.success(knowledgeBaseService.getStrategy(id, (Long) request.getAttribute("userId")));
    }

    /** 更新知识库策略 */
    @PutMapping("/{id}/strategy")
    public Result<KnowledgeBase> updateStrategy(@PathVariable Long id, @RequestBody KnowledgeBase body, HttpServletRequest request) {
        // 调用知识库服务校验管理权限并保存策略
        return Result.success(knowledgeBaseService.updateStrategy(id, body, (Long) request.getAttribute("userId")));
    }
}
