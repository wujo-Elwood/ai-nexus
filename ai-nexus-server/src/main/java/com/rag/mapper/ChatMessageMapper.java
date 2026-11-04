package com.rag.mapper;

import com.rag.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    // 按用户和会话编号查询聊天消息列表
    List<ChatMessage> findByUserIdAndSessionId(@Param("userId") Long userId,
                                               @Param("sessionId") Long sessionId);

    // 新增聊天消息
    int insert(ChatMessage message);

    // 按用户和会话编号删除聊天消息
    int deleteByUserIdAndSessionId(@Param("userId") Long userId,
                                   @Param("sessionId") Long sessionId);

    // 检查反馈目标是否为当前用户的助手消息
    int countAssistantMessagesByUserIdAndMessageId(@Param("userId") Long userId,
                                                   @Param("messageId") Long messageId);
}
