package com.rag.agent.tools;

/**
 * 通用智能体执行过程监听器
 * 服务层每执行一步都会回调对应方法，控制器侧用它把步骤实时推送到 SSE，
 * 测试侧用它收集事件做断言
 */
public interface AgentToolListener {

    /** 会话开始，返回本次运行ID和当前可用工具 */
    void onOpen(String runId, Object tools);

    /** 一轮工具调用开始 */
    void onStepStart(int stepNo, String toolName, String arguments);

    /** 一轮工具调用结束 */
    void onStepResult(int stepNo, String result, long costMs, String status);

    /** 模型给出最终回答 */
    void onAnswer(String content);

    /** 全部结束 */
    void onDone(String runId);

    /** 执行出错 */
    void onError(String message);
}
