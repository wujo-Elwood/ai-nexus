package com.rag.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 通用工具智能体对话请求
 * message 为本次用户输入，history 为可选的历史对话用于多轮上下文
 */
@Data
public class AgentToolChatRequest {

    /** 用户本次输入 */
    private String message;

    /** 历史对话（可选），role 取值 user/assistant */
    private List<HistoryTurn> history;

    /** 历史对话轮次 */
    @Data
    public static class HistoryTurn {
        /** 角色：user/assistant */
        private String role;
        /** 对话内容 */
        private String content;
    }
}
