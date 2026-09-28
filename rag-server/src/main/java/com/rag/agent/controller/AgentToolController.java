package com.rag.agent.controller;

import com.rag.agent.dto.AgentToolChatRequest;
import com.rag.agent.tools.AgentToolService;
import com.rag.agent.tools.ToolRegistry;
import com.rag.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 通用工具智能体接口
 * 对话走 SSE 流式推送，每个工具调用步骤都会实时下发；工具清单用于前端展示
 */
@RestController
@RequestMapping("/api/agent-tools")
public class AgentToolController {

    private final AgentToolService agentToolService;
    private final ToolRegistry toolRegistry;

    public AgentToolController(AgentToolService agentToolService, ToolRegistry toolRegistry) {
        this.agentToolService = agentToolService;
        this.toolRegistry = toolRegistry;
    }

    /** 工具智能体对话，SSE 事件：open/step_start/step_result/answer/done/error */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@RequestBody AgentToolChatRequest request, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BusinessException(400, "消息不能为空");
        }
        return ResponseEntity.ok(agentToolService.chat(request));
    }

    /** 当前注册的工具清单 */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return toolRegistry.describe();
    }
}
