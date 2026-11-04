package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求 DTO
 * 用于接收用户发送的对话消息，支持关联知识库进行RAG问答
 */
@Data
public class ChatRequest {
    /** 用户消息内容，必填 */
    @NotBlank(message = "Message is required")
    private String message;

    /** 知识库ID，选填。指定后会基于该知识库内容进行RAG检索增强回答 */
    private Long kbId;

    /** 会话ID，选填。为空时新建会话，不为空时继续已有对话 */
    private Long sessionId;
}
