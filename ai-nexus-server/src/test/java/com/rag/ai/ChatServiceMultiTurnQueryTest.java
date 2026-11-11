package com.rag.ai;

import com.rag.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮检索改写的纯函数测试：
 * 追问识别（什么时候值得多花一次模型调用）与历史格式化（喂给模型的历史长什么样）
 */
class ChatServiceMultiTurnQueryTest {

    private ChatMessage message(String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    @Test
    void shouldDetectShortFollowUpQuestions() {
        // 依赖上下文的典型追问
        assertTrue(ChatService.looksLikeFollowUp("它的上限是多少"));
        assertTrue(ChatService.looksLikeFollowUp("那成本呢"));
        assertTrue(ChatService.looksLikeFollowUp("还有别的方案吗"));
        assertTrue(ChatService.looksLikeFollowUp("这个怎么配置"));
        assertTrue(ChatService.looksLikeFollowUp("刚才说的那个参数"));
    }

    @Test
    void shouldNotDetectIndependentQuestionAsFollowUp() {
        // 自带完整语义的独立问题不应触发改写，避免每次多花一次模型调用
        assertFalse(ChatService.looksLikeFollowUp("请说明企业知识库的分片策略"));
        assertFalse(ChatService.looksLikeFollowUp("介绍一下 Qdrant 的向量重建流程和回滚方式"));
        assertFalse(ChatService.looksLikeFollowUp(""));
        assertFalse(ChatService.looksLikeFollowUp(null));
        // 超过 30 字的长句一律不改写
        assertFalse(ChatService.looksLikeFollowUp(
                "我想了解一下这个系统在处理超长文档时切片和向量化分别是怎么实现的以及会不会丢内容"));
    }

    @Test
    void shouldReturnEmptyHistoryWhenOnlyCurrentMessage() {
        // 只有当前这一条时没有可用的上文
        List<ChatMessage> history = new ArrayList<>();
        history.add(message("user", "它的上限是多少"));
        assertEquals("", ChatService.formatHistory(history, 3));
        assertEquals("", ChatService.formatHistory(null, 3));
    }

    @Test
    void shouldFormatOnlyRecentTurnsAndSkipCurrentMessage() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(message("user", "第一轮问题"));
        history.add(message("assistant", "第一轮回答"));
        history.add(message("user", "第二轮问题"));
        history.add(message("assistant", "第二轮回答"));
        history.add(message("user", "它的上限是多少"));

        String text = ChatService.formatHistory(history, 1);
        // 只保留最近 1 轮（2 条消息），且不包含当前这条问题
        assertTrue(text.contains("第二轮问题"), text);
        assertTrue(text.contains("第二轮回答"), text);
        assertFalse(text.contains("第一轮"), text);
        assertFalse(text.contains("它的上限是多少"), text);
        assertTrue(text.startsWith("用户："), text);
        assertTrue(text.contains("助手："), text);
    }

    @Test
    void shouldTruncateLongHistoryMessage() {
        StringBuilder longAnswer = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longAnswer.append("答案");
        }
        List<ChatMessage> history = new ArrayList<>();
        history.add(message("user", "知识库怎么切片"));
        history.add(message("assistant", longAnswer.toString()));
        history.add(message("user", "它的上限是多少"));

        String text = ChatService.formatHistory(history, 3);
        // 单条历史被截断到 200 字并加省略号，避免改写请求本身超长
        assertTrue(text.contains("…"), text);
        assertTrue(text.length() < longAnswer.length(), "历史文本应短于原始回答");
    }
}
