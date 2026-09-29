package com.rag.agent.tools;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 智能体流式模型抽象
 * 屏蔽底层实现（自建 OpenAI 兼容 SSE 客户端），便于测试时注入脚本化的假模型
 */
public interface AgentModel {

    /**
     * 流式生成一轮回答
     *
     * @param messages      完整对话消息列表
     * @param openAiTools   OpenAI function 格式的工具定义（可为空）
     * @param deltaConsumer 文本增量回调（流式输出过程中逐段回调）
     * @return 本轮最终响应（可能包含工具调用请求）
     */
    Response<AiMessage> stream(List<ChatMessage> messages,
                               List<Map<String, Object>> openAiTools,
                               Consumer<String> deltaConsumer) throws Exception;
}
