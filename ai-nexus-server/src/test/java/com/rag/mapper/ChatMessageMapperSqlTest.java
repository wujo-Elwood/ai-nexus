package com.rag.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天消息 SQL 测试
 * 验证聊天记录的读写和删除都限定到当前用户
 */
class ChatMessageMapperSqlTest {

    /**
     * 读取指定 MyBatis 映射语句
     */
    private String statement(String xmlFile, String tag, String statementId) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(xmlFile)) {
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String openingTag = "<" + tag + " id=\"" + statementId + "\"";
            int start = mapperXml.indexOf(openingTag);
            assertTrue(start >= 0, xmlFile + " 缺少语句 " + statementId);
            int end = mapperXml.indexOf("</" + tag + ">", start);
            return mapperXml.substring(start, end).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 新聊天消息必须保存认证用户编号
     */
    @Test
    void insertShouldPersistUserId() throws Exception {
        String insert = statement("mapper/ChatMessageMapper.xml", "insert", "insert");

        assertTrue(insert.contains("user_id"), "新消息必须写入 user_id");
        assertTrue(insert.contains("#{userid}"), "新消息的 user_id 必须来自实体参数");
    }

    /**
     * 会话历史读取与删除必须同时限制用户编号和会话编号
     */
    @Test
    void historyAndDeleteShouldBeScopedToUserAndSession() throws Exception {
        String select = statement("mapper/ChatMessageMapper.xml", "select", "findByUserIdAndSessionId");
        String delete = statement("mapper/ChatMessageMapper.xml", "delete", "deleteByUserIdAndSessionId");

        assertTrue(select.contains("user_id = #{userid}"), "历史查询必须限制当前用户");
        assertTrue(select.contains("session_id = #{sessionid}"), "历史查询必须限制当前会话");
        assertTrue(delete.contains("user_id = #{userid}"), "删除必须限制当前用户");
        assertTrue(delete.contains("session_id = #{sessionid}"), "删除必须限制当前会话");
    }

    /**
     * CHAT 反馈的读取、删除和新增必须验证消息属于当前用户
     */
    @Test
    void chatFeedbackShouldBeScopedToOwnerAssistantMessage() throws Exception {
        String exists = statement("mapper/ChatMessageMapper.xml", "select",
                "countAssistantMessagesByUserIdAndMessageId");
        String feedbackSelect = statement("mapper/FeedbackMapper.xml", "select", "findByChatSessionId");
        String feedbackDelete = statement("mapper/FeedbackMapper.xml", "delete",
                "deleteChatFeedbackForUser");
        String feedbackInsert = statement("mapper/FeedbackMapper.xml", "insert",
                "insertChatFeedbackForUser");

        assertTrue(exists.contains("user_id = #{userid}"), "反馈对象校验必须限制当前用户");
        assertTrue(exists.contains("role = 'assistant'"), "反馈对象必须是助手消息");
        assertTrue(feedbackSelect.contains("m.user_id = #{userid}"), "反馈状态查询必须限制当前用户");
        assertTrue(feedbackDelete.contains("m.user_id = #{userid}"), "反馈删除必须限制当前用户");
        assertTrue(feedbackInsert.contains("m.user_id = #{userid}"), "反馈写入必须限制当前用户");
        assertTrue(feedbackInsert.contains("m.role = 'assistant'"), "反馈写入必须限制助手消息");
    }
}
