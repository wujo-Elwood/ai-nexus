package com.rag.controller;

import com.rag.dto.ChatRequest;
import com.rag.ai.ChatService;
import com.rag.common.BusinessException;
import com.rag.entity.ChatMessage;
import com.rag.entity.Feedback;
import com.rag.mapper.ChatMessageMapper;
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
    private ChatMessageMapper chatMessageMapper;

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
        String response = chatService.chat(userId, sessionId, request.getMessage(), request.getKbId());
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
        SseEmitter emitter = chatService.chatStream(userId, sessionId, request.getMessage(), request.getKbId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /** 查询某个会话的历史消息列表 */
    @GetMapping("/history/{sessionId}")
    public Result<List<ChatMessage>> getHistory(@PathVariable Long sessionId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(chatService.getSessionMessages(userId, sessionId));
    }

    /** 删除某个会话的所有聊天记录 */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        chatService.deleteSession(userId, sessionId);
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

    /**
     * 提交答案质量反馈（有帮助/无帮助）
     * helpful 传 0/1 为提交或换评价（每条消息只保留最新一条）；传 null 为取消反馈；
     * source 区分消息来源：CHAT=AI 聊天（默认），AGENT=智能体消息
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody Feedback feedback, HttpServletRequest request) {
        // 第1步：消息编号必须给出，来源必须合法（缺省 CHAT 兼容旧调用）
        if (feedback.getMessageId() == null) {
            return Result.error("缺少 messageId");
        }
        String source = feedback.getSource() == null || feedback.getSource().isBlank()
                ? Feedback.SOURCE_CHAT : feedback.getSource().trim().toUpperCase();
        if (!Feedback.SOURCE_CHAT.equals(source) && !Feedback.SOURCE_AGENT.equals(source)) {
            return Result.error("source 仅支持 CHAT 或 AGENT");
        }
        feedback.setSource(source);
        if (feedback.getHelpful() != null && feedback.getHelpful() != 0 && feedback.getHelpful() != 1) {
            return Result.error("helpful 仅支持 0、1 或 null（取消）");
        }

        // CHAT 反馈必须关联当前用户自己的助手消息，SQL 写入和删除也再次校验归属
        if (Feedback.SOURCE_CHAT.equals(source)) {
            Long userId = (Long) request.getAttribute("userId");
            if (chatMessageMapper.countAssistantMessagesByUserIdAndMessageId(userId, feedback.getMessageId()) == 0) {
                throw new BusinessException(404, "聊天消息不存在");
            }
            feedbackMapper.deleteChatFeedbackForUser(feedback.getMessageId(), userId);
            if (feedback.getHelpful() != null) {
                feedbackMapper.insertChatFeedbackForUser(feedback, userId);
            }
            return Result.success();
        }

        // AGENT 反馈保持原有处理逻辑
        feedbackMapper.deleteByMessageAndSource(feedback.getMessageId(), source);
        if (feedback.getHelpful() != null) {
            feedbackMapper.insert(feedback);
        }
        return Result.success();
    }

    /** 生成唯一的会话ID：毫秒时间戳+一位随机数（13-14位），确保不超过 JS 安全整数范围 */
    private Long generateSessionId() {
        return System.currentTimeMillis() * 10 + (long) (Math.random() * 10);
    }
}
