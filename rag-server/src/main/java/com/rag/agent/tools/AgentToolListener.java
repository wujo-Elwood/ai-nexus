package com.rag.agent.tools;

/**
 * 通用智能体执行过程监听器
 * 服务层每执行一步都会回调对应方法，控制器侧用它把步骤实时推送到 SSE，
 * 测试侧用它收集事件做断言
 */
public interface AgentToolListener {

    /** 会话开始，返回本次运行ID和当前可用工具 */
    void onOpen(String runId, Object tools);

    /** 一轮开始时的状态提示（如"正在分析问题…"），空串表示清除 */
    void onStatus(String message);

    /** 一轮工具调用开始 */
    void onStepStart(int stepNo, String toolName, String arguments);

    /** 一轮工具调用结束 */
    void onStepResult(int stepNo, String result, long costMs, String status);

    /** 模型输出的流式文本增量 */
    void onAnswerDelta(String delta);

    /** 本轮流出了工具调用，需要清空已流出的前导文本 */
    void onAnswerReset();

    /** 模型给出最终回答（全量，用于和流式增量对齐） */
    void onAnswer(String content);

    /** 全部结束 */
    void onDone(String runId);

    /** 执行出错 */
    void onError(String message);
}
