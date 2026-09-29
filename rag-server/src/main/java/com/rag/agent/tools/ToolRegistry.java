package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
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

    private static final Map<String, List<String>> AGENT_TOOLS = Map.of(
            "general-tool", List.of("search_city_location", "get_weather"),
            "currency-converter", List.of("get_exchange_rate", "calculate"),
            "vision-agent", List.of("calculate"),
            "data-agent", List.of("list_tables", "describe_table", "query_database"));

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> toolBeans, ObjectMapper objectMapper) {
        this.tools = toolBeans.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.objectMapper = objectMapper;
    }

    /** 按智能体编码解析可用工具子集；未登记的编码返回 null 表示不限制 */
    public List<String> toolsForAgent(String agentCode) {
        return agentCode == null ? null : AGENT_TOOLS.get(agentCode);
    }

    /** 生成 OpenAI function 格式的工具定义列表，names 为空时返回全部 */
    public List<Map<String, Object>> openAiTools(Collection<String> names) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            if (names != null && !names.isEmpty() && !names.contains(tool.name())) {
                continue;
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (AgentTool.ToolParam param : tool.params()) {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", param.type() == null ? "string" : param.type().toLowerCase());
                property.put("description", param.description());
                properties.put(param.name(), property);
                if (param.required()) {
                    required.add(param.name());
                }
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            if (!required.isEmpty()) {
                parameters.put("required", required);
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", parameters);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", function);
            specs.add(spec);
        }
        return specs;
    }

    /** 工具清单，用于前端展示当前可用工具 */
    public List<Map<String, Object>> describe() {
        return describe(null);
    }

    /** 工具清单（指定子集），names 为空时返回全部 */
    public List<Map<String, Object>> describe(Collection<String> names) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            if (names != null && !names.isEmpty() && !names.contains(tool.name())) {
                continue;
            }
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
}
