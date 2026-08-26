package com.rag.agent.controller;

import com.rag.agent.dto.RunKnowledgeQualityRequest;
import com.rag.agent.entity.AgentRun;
import com.rag.agent.service.AgentService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体管理控制器
 * 提供智能体列表、运行质检、历史记录和报告详情接口
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    /**
     * 创建智能体管理控制器
     */
    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 获取智能体列表
     */
    @GetMapping
    public Result<List<Map<String, Object>>> listAgents() {
        // 第1步：查询当前平台支持的智能体
        return Result.success(agentService.listAgents());
    }

    /**
     * 运行知识库质检智能体
     */
    @PostMapping("/knowledge-quality/run")
    public Result<Map<String, Object>> runKnowledgeQuality(@RequestBody RunKnowledgeQualityRequest request,
                                                           HttpServletRequest httpRequest) {
        // 第1步：读取当前登录用户
        Long userId = (Long) httpRequest.getAttribute("userId");
        // 第2步：运行知识库质检
        return Result.success(agentService.runKnowledgeQuality(request, userId));
    }

    /**
     * 查询智能体运行历史
     */
    @GetMapping("/runs")
    public Result<List<AgentRun>> listRuns(@RequestParam(required = false) Integer limit,
                                           HttpServletRequest httpRequest) {
        // 第1步：读取当前登录用户
        Long userId = (Long) httpRequest.getAttribute("userId");
        // 第2步：查询当前用户运行记录
        return Result.success(agentService.listRuns(userId, limit));
    }

    /**
     * 查询智能体运行详情
     */
    @GetMapping("/runs/{id}")
    public Result<Map<String, Object>> getRunDetail(@PathVariable Long id,
                                                    HttpServletRequest httpRequest) {
        // 第1步：读取当前登录用户
        Long userId = (Long) httpRequest.getAttribute("userId");
        // 第2步：查询运行详情
        return Result.success(agentService.getRunDetail(id, userId));
    }

    /**
     * 删除智能体运行记录
     */
    @DeleteMapping("/runs/{id}")
    public Result<Void> deleteRun(@PathVariable Long id,
                                  HttpServletRequest httpRequest) {
        // 第1步：读取当前登录用户
        Long userId = (Long) httpRequest.getAttribute("userId");
        // 第2步：删除当前用户自己的运行记录
        agentService.deleteRun(id, userId);
        return Result.success();
    }
}
