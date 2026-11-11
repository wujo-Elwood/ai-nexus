package com.rag.agent.controller;

import com.rag.agent.entity.AgentConversation;
import com.rag.agent.entity.AgentConversationMessage;
import com.rag.agent.mapper.AgentConversationMapper;
import com.rag.agent.mapper.AgentConversationMessageMapper;
import com.rag.common.BusinessException;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体对话历史控制器
 * 会话与消息的持久化：创建会话、追加消息、加载历史、删除；数据按用户隔离
 */
@RestController
@RequestMapping("/api/agent-conversations")
public class AgentConversationController {

    /** 标题最大长度 */
    private static final int TITLE_MAX_LENGTH = 80;

    private final AgentConversationMapper conversationMapper;
    private final AgentConversationMessageMapper messageMapper;
    private final com.rag.mapper.FeedbackMapper feedbackMapper;

    public AgentConversationController(AgentConversationMapper conversationMapper,
                                       AgentConversationMessageMapper messageMapper,
                                       com.rag.mapper.FeedbackMapper feedbackMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.feedbackMapper = feedbackMapper;
    }

    /**
     * 创建会话（首条消息发出时由前端调用）
     */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String agentCode = text(body, "agentCode", "");
        String title = text(body, "title", "新对话");
        if (agentCode.isEmpty()) {
            throw new BusinessException(400, "智能体编码不能为空");
        }
        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setAgentCode(agentCode);
        conversation.setTitle(abbreviate(title));
        conversationMapper.insert(conversation);
        return Result.success(conversationView(conversation));
    }

    /**
     * 当前用户在某智能体下的会话列表
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String agentCode, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentConversation conversation : conversationMapper.findByUserAndAgent(userId, agentCode)) {
            result.add(conversationView(conversation));
        }
        return Result.success(result);
    }

    /**
     * 加载会话详情与全部消息（用户隔离）
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        AgentConversation conversation = requireOwned(id, userId);
        List<Map<String, Object>> messages = new ArrayList<>();
        // 一次性读出本会话的反馈状态，避免逐条查询
        Map<Long, Integer> feedbackMap = new LinkedHashMap<>();
        try {
            for (com.rag.entity.Feedback feedback : feedbackMapper.findByAgentConversationId(conversation.getId())) {
                feedbackMap.put(feedback.getMessageId(), feedback.getHelpful());
            }
        } catch (Exception ignored) {
            // 反馈表异常按无反馈处理
        }
        for (AgentConversationMessage message : messageMapper.findByConversationId(conversation.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", message.getId());
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            item.put("stepsJson", message.getStepsJson());
            if (feedbackMap.containsKey(message.getId())) {
                item.put("feedback", feedbackMap.get(message.getId()));
            }
            messages.add(item);
        }
        Map<String, Object> result = conversationView(conversation);
        result.put("messages", messages);
        return Result.success(result);
    }

    /**
     * 追加一条消息（用户消息在发送前存、助手消息在流式完成后存）
     */
    @PostMapping("/{id}/messages")
    public Result<Long> appendMessage(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        requireOwned(id, userId);
        String role = text(body, "role", "");
        String content = text(body, "content", "");
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new BusinessException(400, "role 仅支持 user/assistant");
        }
        if (content.isEmpty()) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        AgentConversationMessage message = new AgentConversationMessage();
        message.setConversationId(id);
        message.setRole(role);
        message.setContent(content);
        message.setStepsJson(text(body, "stepsJson", null));
        messageMapper.insert(message);
        conversationMapper.touch(id);
        // 返回落库后的消息编号，前端用它关联反馈
        return Result.success(message.getId());
    }

    /**
     * 删除会话及其全部消息
     */
    @DeleteMapping("/{id}")
    @Transactional
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        requireOwned(id, userId);
        messageMapper.deleteByConversationId(id);
        conversationMapper.deleteByIdAndUserId(id, userId);
        return Result.success();
    }

    /** 加载归属校验通过的会话，不存在时抛 404 */
    private AgentConversation requireOwned(Long id, Long userId) {
        AgentConversation conversation = conversationMapper.findByIdAndUserId(id, userId);
        if (conversation == null) {
            throw new BusinessException(404, "会话不存在或无权访问");
        }
        return conversation;
    }

    private Map<String, Object> conversationView(AgentConversation conversation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", conversation.getId());
        view.put("agentCode", conversation.getAgentCode());
        view.put("title", conversation.getTitle());
        view.put("updateTime", conversation.getUpdateTime());
        return view;
    }

    private String text(Map<String, Object> body, String key, String defaultValue) {
        String value = body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private String abbreviate(String title) {
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }
}
