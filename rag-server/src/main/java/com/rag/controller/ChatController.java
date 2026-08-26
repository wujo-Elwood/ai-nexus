package com.rag.controller;

import com.rag.dto.ChatRequest;
import com.rag.ai.ChatService;
import com.rag.entity.ChatMessage;
import com.rag.entity.Feedback;
import com.rag.mapper.FeedbackMapper;
import com.rag.service.KnowledgeBaseService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天控制器
 * 提供同步/流式聊天、历史查询、会话删除、召回测试、反馈提交等接口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private FeedbackMapper feedbackMapper;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    /**
     * 同步聊天接口
     * 发送消息后等待大模型返回完整回复
     */
    @PostMapping("/send")
    public Result<String> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        // 第1步：选择知识库时校验当前用户的读取权限
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (request.getKbId() != null) {
            knowledgeBaseService.checkAccess(request.getKbId(), userId);
        }
        // 第2步：生成或沿用会话编号
        Long sessionId = request.getSessionId();
        if (sessionId == null) { sessionId = generateSessionId(); }
        // 第3步：执行同步问答
        String response = chatService.chat(sessionId, request.getMessage(), request.getKbId());
        return Result.success(response);
    }

    /**
     * 流式聊天接口（SSE）
     * 发送消息后通过 Server-Sent Events 逐字推送大模型回复
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        // 第1步：选择知识库时校验当前用户的读取权限
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (request.getKbId() != null) {
            knowledgeBaseService.checkAccess(request.getKbId(), userId);
        }
        // 第2步：生成或沿用会话编号
        Long sessionId = request.getSessionId();
        if (sessionId == null) { sessionId = generateSessionId(); }
        // 第3步：提交流式问答
        SseEmitter emitter = chatService.chatStream(sessionId, request.getMessage(), request.getKbId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /** 查询某个会话的历史消息列表 */
    @GetMapping("/history/{sessionId}")
    public Result<List<ChatMessage>> getHistory(@PathVariable Long sessionId) {
        return Result.success(chatService.getSessionMessages(sessionId));
    }

    /** 删除某个会话的所有聊天记录 */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId);
        return Result.success();
    }

    /** 召回测试：输入问题和知识库ID，返回召回的文本块列表（用于调试检索质量） */
    @PostMapping("/recall-test")
    public Result<List<Map<String, Object>>> recallTest(@RequestBody Map<String, Object> request,
                                                        HttpServletRequest httpRequest) {
        // 第1步：读取召回问题和知识库编号
        String message = (String) request.get("message");
        Long kbId = Long.valueOf(request.get("kbId").toString());
        // 第2步：校验当前用户是否有权读取知识库
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkAccess(kbId, userId);
        // 第3步：执行召回测试
        return Result.success(chatService.recallTest(message, kbId));
    }

    /** 检索调试：返回查询改写、候选、分数和过滤原因 */
    @PostMapping("/diagnose")
    public Result<Map<String, Object>> diagnose(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        // 校验知识库读取权限后执行不调用大模型的检索诊断
        Long kbId = Long.valueOf(body.get("kbId").toString());
        knowledgeBaseService.checkAccess(kbId, (Long) request.getAttribute("userId"));
        return Result.success(chatService.diagnoseRecall(String.valueOf(body.getOrDefault("message", "")), kbId, body));
    }

    /** 提交答案质量反馈（有帮助/无帮助） */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody Feedback feedback) {
        feedbackMapper.insert(feedback);
        return Result.success();
    }

    /** 生成唯一的会话ID */
    private Long generateSessionId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
