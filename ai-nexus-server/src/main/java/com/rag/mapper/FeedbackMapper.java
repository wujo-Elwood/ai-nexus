package com.rag.mapper;

import com.rag.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 答案反馈 Mapper 接口
 * 每条消息只保留一条反馈：换评价先删后插，取消即删除
 */
@Mapper
public interface FeedbackMapper {
    /** 插入一条反馈记录 */
    int insert(Feedback feedback);

    /** 删除指定来源下某条消息的反馈（取消反馈或换评价前先清理） */
    int deleteByMessageAndSource(@Param("messageId") Long messageId, @Param("source") String source);

    /** 删除当前用户对自己聊天消息的反馈 */
    int deleteChatFeedbackForUser(@Param("messageId") Long messageId, @Param("userId") Long userId);

    /** 新增当前用户对自己助手消息的反馈 */
    int insertChatFeedbackForUser(@Param("feedback") Feedback feedback, @Param("userId") Long userId);

    /** 查询当前用户某聊天会话内全部消息的反馈状态 */
    List<Feedback> findByChatSessionId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /** 查询某智能体会话内全部消息的反馈状态 */
    List<Feedback> findByAgentConversationId(@Param("conversationId") Long conversationId);
}
