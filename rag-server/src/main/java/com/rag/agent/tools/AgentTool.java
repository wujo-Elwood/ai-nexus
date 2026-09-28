package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 通用智能体工具接口
 * 每个工具声明自己的元数据供模型选择，并处理模型给出的 JSON 参数；
 * 框架负责把工具元数据转换为 LangChain4j 工具规范、执行调用和记录步骤
 */
public interface AgentTool {

    /** 工具名称，供模型调用时使用，建议小写下划线风格 */
    String name();

    /** 工具功能描述，供模型决策是否调用 */
    String description();

    /** 参数声明列表 */
    List<ToolParam> params();

    /**
     * 执行工具
     *
     * @param arguments 模型给出的参数（JSON 对象节点）
     * @return 返回给模型看的文本结果
     */
    String execute(JsonNode arguments) throws Exception;

    /** 工具参数元数据 */
    record ToolParam(String name, String type, String description, boolean required) {
    }
}
