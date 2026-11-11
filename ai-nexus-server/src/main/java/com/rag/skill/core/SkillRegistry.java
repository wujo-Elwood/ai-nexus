package com.rag.skill.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.common.BusinessException;
import com.rag.skill.entity.SkillInfo;
import com.rag.skill.mapper.AgentSkillMapper;
import com.rag.skill.mapper.SkillMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 技能注册中心
 * 收集容器内所有 AgentTool 实现形成技能目录；启动时把目录同步到 skill 表，
 * 智能体与技能的绑定关系、技能启停由数据库驱动（技能管理页可配置）；
 * 数据库不可用时回退到内置分配，保证智能体对话可用
 */
@Slf4j
@Component
public class SkillRegistry {

    /** 已知智能体的默认技能分配：启动时为没有任何绑定的智能体自动补齐，数据库异常时也作为兜底 */
    private static final Map<String, List<String>> DEFAULT_BINDINGS = Map.of(
            "general-tool", List.of("search_city_location", "get_weather"),
            "currency-converter", List.of("get_exchange_rate", "calculate"),
            "vision-agent", List.of("calculate"),
            "data-agent", List.of("list_tables", "describe_table", "query_database"),
            "image-agent", List.of("generate_image"),
            "data-analysis", List.of("describe_spreadsheet", "analyze_spreadsheet"),
            "news-assistant", List.of("fetch_ai_news", "send_email"),
            "web-search", List.of("web_search"));

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;
    private final SkillMapper skillMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final Executor skillExecuteExecutor;
    private final long executeTimeoutMillis;

    public SkillRegistry(List<AgentTool> toolBeans, ObjectMapper objectMapper,
                         SkillMapper skillMapper, AgentSkillMapper agentSkillMapper,
                         @Qualifier("skillExecuteExecutor") Executor skillExecuteExecutor,
                         @Value("${skill.execute-timeout-ms:120000}") long executeTimeoutMillis) {
        this.tools = toolBeans.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.objectMapper = objectMapper;
        this.skillMapper = skillMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.skillExecuteExecutor = skillExecuteExecutor;
        this.executeTimeoutMillis = executeTimeoutMillis;
    }

    /** 启动时同步技能目录到 skill 表，并为没有绑定的已知智能体补齐默认绑定 */
    @PostConstruct
    public void syncFromCode() {
        syncSkillCatalog();
        provisionDefaultBindings();
    }

    /** 把代码内技能目录同步到 skill 表：新增技能插入，描述变化更新 */
    void syncSkillCatalog() {
        try {
            for (AgentTool tool : tools.values()) {
                skillMapper.upsertSkill(tool.name(), tool.description());
            }
        } catch (Exception e) {
            log.warn("技能目录同步失败（skill 表可能未创建，管理页启停将不可用）: {}", e.getMessage());
        }
    }

    /** 为没有任何绑定的已知智能体补齐默认绑定，保证迁移脚本缺失时智能体仍按默认技能工作 */
    void provisionDefaultBindings() {
        try {
            for (Map.Entry<String, List<String>> entry : DEFAULT_BINDINGS.entrySet()) {
                List<String> bound = agentSkillMapper.findSkillNamesByAgent(entry.getKey());
                if (bound == null || bound.isEmpty()) {
                    agentSkillMapper.insertBatch(entry.getKey(), entry.getValue());
                    log.info("已为智能体 {} 补齐默认技能绑定: {}", entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("默认技能绑定同步失败: {}", e.getMessage());
        }
    }

    /** 按智能体编码解析绑定的技能子集并过滤停用项；编码为空返回 null 表示不限制 */
    public List<String> skillsForAgent(String agentCode) {
        if (agentCode == null) {
            return null;
        }
        try {
            List<String> bound = agentSkillMapper.findSkillNamesByAgent(agentCode);
            if (bound == null || bound.isEmpty()) {
                return List.of();
            }
            Set<String> disabled = disabledSkillNames();
            return bound.stream().filter(name -> !disabled.contains(name)).toList();
        } catch (Exception e) {
            log.warn("读取技能绑定失败，回退默认分配: {}", e.getMessage());
            return DEFAULT_BINDINGS.get(agentCode);
        }
    }

    /** 当前停用的技能名集合；查询失败视为全部启用 */
    public Set<String> disabledSkillNames() {
        try {
            return skillMapper.findAll().stream()
                    .filter(skill -> skill.getEnabled() == null || skill.getEnabled() == 0)
                    .map(SkillInfo::getSkillName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("读取技能启用状态失败，按全部启用处理: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 判断技能是否在代码目录中登记 */
    public boolean exists(String skillName) {
        return skillName != null && tools.containsKey(skillName);
    }

    /** 查询智能体的默认绑定技能（平台内置，不可通过管理页移除）；未知智能体返回空列表 */
    public List<String> defaultSkillsFor(String agentCode) {
        return agentCode == null ? List.of() : DEFAULT_BINDINGS.getOrDefault(agentCode, List.of());
    }

    /** 全部默认绑定，按智能体编码分组，供管理页展示只读标识 */
    public Map<String, List<String>> defaultBindings() {
        return Map.copyOf(DEFAULT_BINDINGS);
    }

    /** 校验绑定保存：默认绑定的技能不允许移除，违反时抛出业务异常 */
    public void validateBindingChange(String agentCode, List<String> skillNames) {
        List<String> missing = defaultSkillsFor(agentCode).stream()
                .filter(defaultSkill -> skillNames == null || !skillNames.contains(defaultSkill))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "默认绑定的技能不能移除：" + String.join("、", missing));
        }
    }

    /** 全部技能编码 */
    public Set<String> allSkillNames() {
        return tools.keySet();
    }

    /** 生成 OpenAI function 格式的技能定义列表；names 为 null 返回全部，为空列表表示一个都不暴露（停用技能不暴露给模型） */
    public List<Map<String, Object>> openAiTools(Collection<String> names) {
        if (names != null && names.isEmpty()) {
            return List.of();
        }
        Set<String> disabled = disabledSkillNames();
        List<Map<String, Object>> specs = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            if (disabled.contains(tool.name())) {
                continue;
            }
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

    /** 技能清单，用于前端展示当前可用技能 */
    public List<Map<String, Object>> describe() {
        return describe(null);
    }

    /** 技能清单（指定子集）；names 为 null 返回全部，为空列表返回空 */
    public List<Map<String, Object>> describe(Collection<String> names) {
        if (names != null && names.isEmpty()) {
            return List.of();
        }
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

    /** 按名称执行技能，argumentsJson 为模型给出的参数 JSON 字符串 */
    public String execute(String toolName, String argumentsJson) throws Exception {
        return execute(toolName, argumentsJson, null);
    }

    /**
     * 按名称执行技能并注入当前用户上下文
     * userId 会写入参数 JSON 的 userId 字段供技能读取，该字段不在 params() 中声明，模型不可见；
     * 所有技能统一在独立线程池中带超时执行，防止单个技能卡死整个对话循环
     */
    public String execute(String toolName, String argumentsJson, Long userId) throws Exception {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("未知技能：" + toolName);
        }
        if (disabledSkillNames().contains(toolName)) {
            return "技能 " + toolName + " 已停用，无法执行；请告知用户到「技能管理」中启用后再试";
        }
        JsonNode args = (argumentsJson == null || argumentsJson.isBlank())
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(argumentsJson);
        if (userId != null && args.isObject()) {
            ((ObjectNode) args).put("userId", userId);
        }
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    return tool.execute(args);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, skillExecuteExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("技能 {} 执行队列已满", toolName);
            return "技能 " + toolName + " 执行队列繁忙，请告知用户稍后重试";
        }
        try {
            return future.get(executeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("技能 {} 执行超时（{} ms）", toolName, executeTimeoutMillis);
            return "技能 " + toolName + " 执行超时（超过 " + (executeTimeoutMillis / 1000) + " 秒），"
                    + "请告知用户稍后重试，或到「技能管理」检查该技能依赖的外部服务";
        } catch (ExecutionException e) {
            future.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException wrapped && wrapped.getCause() instanceof Exception inner) {
                throw inner;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("技能执行失败", cause);
        }
    }
}
