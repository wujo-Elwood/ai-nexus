package com.rag.agent.controller;

import com.rag.agent.entity.AgentConversation;
import com.rag.agent.entity.AgentConversationMessage;
import com.rag.agent.mapper.AgentConversationMapper;
import com.rag.agent.mapper.AgentConversationMessageMapper;
import com.rag.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能体对话历史控制器测试：验证创建、用户隔离、消息追加校验与级联删除
 */
class AgentConversationControllerTest {

    private AgentConversationMapper conversationMapper;
    private AgentConversationMessageMapper messageMapper;
    private AgentConversationController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        messageMapper = mock(AgentConversationMessageMapper.class);
        controller = new AgentConversationController(conversationMapper, messageMapper,
                org.mockito.Mockito.mock(com.rag.mapper.FeedbackMapper.class));
        request = mock(HttpServletRequest.class);
        when(request.getAttribute("userId")).thenReturn(1L);
    }

    private Map<String, Object> body(String... keyValuePairs) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            body.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return body;
    }

    private AgentConversation conversation(Long id) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(id);
        conversation.setUserId(1L);
        conversation.setAgentCode("news-assistant");
        conversation.setTitle("标题");
        return conversation;
    }

    @Test
    void createStoresTitleAndUser() {
        var result = controller.create(body("agentCode", "news-assistant", "title", "今天的AI简报"), request);
        ArgumentCaptor<AgentConversation> captor = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertEquals("news-assistant", captor.getValue().getAgentCode());
        assertEquals("今天的AI简报", captor.getValue().getTitle());
        // 单测桩不回填主键，ID 断言由真实库回填路径覆盖；此处验证字段透传
        assertEquals("news-assistant", result.getData().get("agentCode"));
    }

    @Test
    void detailEnforcesUserIsolation() {
        when(conversationMapper.findByIdAndUserId(9L, 1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> controller.detail(9L, request));
    }

    @Test
    void appendMessageValidatesRole() {
        when(conversationMapper.findByIdAndUserId(5L, 1L)).thenReturn(conversation(5L));
        assertThrows(BusinessException.class,
                () -> controller.appendMessage(5L, body("role", "system", "content", "x"), request));
        verify(messageMapper, never()).insert(any(AgentConversationMessage.class));
    }

    @Test
    void appendMessageStoresContentAndTouchesConversation() {
        when(conversationMapper.findByIdAndUserId(5L, 1L)).thenReturn(conversation(5L));
        controller.appendMessage(5L, body("role", "assistant", "content", "简报内容", "stepsJson", "[]"), request);
        ArgumentCaptor<AgentConversationMessage> captor = ArgumentCaptor.forClass(AgentConversationMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals("assistant", captor.getValue().getRole());
        assertEquals("[]", captor.getValue().getStepsJson());
        verify(conversationMapper).touch(5L);
    }

    @Test
    void deleteCascadesMessages() {
        when(conversationMapper.findByIdAndUserId(5L, 1L)).thenReturn(conversation(5L));
        controller.delete(5L, request);
        verify(messageMapper).deleteByConversationId(5L);
        verify(conversationMapper).deleteByIdAndUserId(5L, 1L);
    }

    @Test
    void deleteRejectsMissingConversation() {
        when(conversationMapper.findByIdAndUserId(9L, 1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> controller.delete(9L, request));
        verify(messageMapper, never()).deleteByConversationId(9L);
    }
}
