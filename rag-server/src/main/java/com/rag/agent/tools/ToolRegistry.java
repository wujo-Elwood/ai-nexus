package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通用智能体工具注册中心
 * 收集容器内所有 AgentTool 实现，向模型暴露工具规范，并提供统一的执行入口
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> toolBeans, ObjectMapper objectMapper) {
        this.tools = toolBeans.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.objectMapper = objectMapper;
    }

    /** 生成给模型看的工具规范列表 */
    public List<ToolSpecification> specifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description());
            for (AgentTool.ToolParam param : tool.params()) {
                JsonSchemaProperty type = typeProperty(param.type());
                JsonSchemaProperty description = new JsonSchemaProperty("description", param.description());
                if (param.required()) {
                    builder.addParameter(param.name(), type, description);
                } else {
                    builder.addOptionalParameter(param.name(), type, description);
                }
            }
            specs.add(builder.build());
        }
        return specs;
    }

    /** 工具清单，用于前端展示当前可用工具 */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            List<Map<String, Object>> params = new ArrayList<>();
            for (AgentTool.ToolParam param : tool.params()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", param.name());
                p.put("type", param.type());
                p.put("description", param.description());
                p.put("required", param.required());
                params.add(p);
            }
            item.put("params", params);
            list.add(item);
        }
        return list;
    }

    /** 按名称执行工具，argumentsJson 为模型给出的参数 JSON 字符串 */
    public String execute(String toolName, String argumentsJson) throws Exception {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + toolName);
        }
        JsonNode args = (argumentsJson == null || argumentsJson.isBlank())
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(argumentsJson);
        return tool.execute(args);
    }

    /** 参数类型字符串映射为 JSON Schema 类型声明 */
    private JsonSchemaProperty typeProperty(String type) {
        switch (type == null ? "" : type.toLowerCase()) {
            case "number":
                return JsonSchemaProperty.NUMBER;
            case "integer":
                return JsonSchemaProperty.INTEGER;
            case "boolean":
                return JsonSchemaProperty.BOOLEAN;
            default:
                return JsonSchemaProperty.STRING;
        }
    }
}
