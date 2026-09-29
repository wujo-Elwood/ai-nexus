package com.rag.agent.controller;

import com.rag.agent.service.AgentService;
import com.rag.vo.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体管理控制器
 * 提供智能体总览列表接口
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
}
