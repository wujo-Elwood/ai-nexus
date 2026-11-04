package com.rag.skill.controller;

import com.rag.common.BusinessException;
import com.rag.rbac.annotation.RequirePermission;
import com.rag.skill.core.SkillRegistry;
import com.rag.skill.entity.AgentSkillBinding;
import com.rag.skill.entity.AgentUsageStat;
import com.rag.skill.entity.SkillUsageStat;
import com.rag.skill.mapper.AgentSkillMapper;
import com.rag.skill.mapper.SkillMapper;
import com.rag.vo.Result;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能管理控制器
 * 提供技能目录查询、启停开关和智能体绑定配置接口
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final SkillMapper skillMapper;
    private final AgentSkillMapper agentSkillMapper;

    public SkillController(SkillRegistry skillRegistry, SkillMapper skillMapper, AgentSkillMapper agentSkillMapper) {
        this.skillRegistry = skillRegistry;
        this.skillMapper = skillMapper;
        this.agentSkillMapper = agentSkillMapper;
    }

    /**
     * 技能清单：代码目录 + 启用状态 + 绑定的智能体列表
     */
    @GetMapping
    @RequirePermission("skills:view")
    public Result<List<Map<String, Object>>> list() {
        // 第1步：读取代码内技能目录与停用集合
        Set<String> disabled = skillRegistry.disabledSkillNames();
        Map<String, List<String>> bindings = groupBindings();
        // 第2步：组装每个技能的展示数据（复制一份再扩展，不改动注册中心返回的原始条目）
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : skillRegistry.describe()) {
            String name = String.valueOf(item.get("name"));
            Map<String, Object> entry = new LinkedHashMap<>(item);
            entry.put("enabled", !disabled.contains(name));
            entry.put("agents", bindings.getOrDefault(name, List.of()));
            result.add(entry);
        }
        return Result.success(result);
    }

    /**
     * 启用或停用技能
     */
    @PutMapping("/{skillName}/enabled")
    @RequirePermission("skills:view")
    public Result<Void> updateEnabled(@PathVariable String skillName, @RequestBody Map<String, Object> body) {
        // 第1步：校验技能存在与参数合法
        if (!skillRegistry.exists(skillName)) {
            throw new BusinessException(404, "技能不存在：" + skillName);
        }
        Integer enabled = parseEnabled(body.get("enabled"));
        // 第2步：落库；skill 表没有对应行（启动同步前）时先补齐
        skillMapper.upsertSkill(skillName, skillDescription(skillName));
        skillMapper.updateEnabled(skillName, enabled);
        return Result.success();
    }

    /**
     * 读取全部智能体的技能绑定，按智能体编码分组（与保存接口的入参结构对称）
     */
    @GetMapping("/bindings")
    @RequirePermission("skills:view")
    public Result<Map<String, List<String>>> bindings() {
        return Result.success(groupBindingsByAgent());
    }

    /**
     * 保存某智能体的技能绑定（全量替换）
     */
    @PutMapping("/bindings")
    @RequirePermission("skills:view")
    @Transactional
    public Result<Void> saveBindings(@RequestBody Map<String, Object> body) {
        // 第1步：解析并校验请求
        String agentCode = body.get("agentCode") == null ? "" : String.valueOf(body.get("agentCode")).trim();
        if (agentCode.isEmpty()) {
            throw new BusinessException(400, "智能体编码不能为空");
        }
        @SuppressWarnings("unchecked")
        List<String> skillNames = body.get("skillNames") == null ? List.of() : (List<String>) body.get("skillNames");
        for (String name : skillNames) {
            if (!skillRegistry.exists(name)) {
                throw new BusinessException(400, "未知技能：" + name);
            }
        }
        // 第2步：默认绑定的技能不允许移除
        skillRegistry.validateBindingChange(agentCode, skillNames);
        // 第3步：全量替换绑定关系
        agentSkillMapper.deleteByAgent(agentCode);
        if (!skillNames.isEmpty()) {
            agentSkillMapper.insertBatch(agentCode, new ArrayList<>(new LinkedHashSet<>(skillNames)));
        }
        return Result.success();
    }

    /**
     * 技能调用统计：近 N 天各技能与各智能体的调用量、成功率和平均耗时
     */
    @GetMapping("/stats")
    @RequirePermission("skills:view")
    public Result<Map<String, Object>> stats(@RequestParam(defaultValue = "7") Integer days) {
        // 第1步：规范统计窗口（1-90 天）
        int safeDays = days == null ? 7 : Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        // 第2步：按技能与按智能体分组聚合
        List<SkillUsageStat> skills = skillMapper.summarizeBySkill(since);
        List<AgentUsageStat> agents = skillMapper.summarizeByAgent(since);
        // 第3步：从技能分组结果推导总量，避免额外一次扫描
        long totalCalls = 0;
        long successCalls = 0;
        double weightedCost = 0;
        for (SkillUsageStat stat : skills) {
            long calls = stat.getCalls() == null ? 0 : stat.getCalls();
            totalCalls += calls;
            successCalls += stat.getSuccessCount() == null ? 0 : stat.getSuccessCount();
            weightedCost += (stat.getAvgCostMs() == null ? 0 : stat.getAvgCostMs()) * calls;
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("calls", totalCalls);
        total.put("successCalls", successCalls);
        total.put("failedCalls", totalCalls - successCalls);
        total.put("successRate", totalCalls == 0 ? 0.0 : Math.round(successCalls * 1000.0 / totalCalls) / 10.0);
        total.put("avgCostMs", totalCalls == 0 ? 0 : Math.round(weightedCost / totalCalls));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", safeDays);
        result.put("total", total);
        result.put("skills", skills);
        result.put("agents", agents);
        return Result.success(result);
    }

    /**
     * 查询各智能体的默认绑定技能（平台内置，不可移除）
     */
    @GetMapping("/default-bindings")
    @RequirePermission("skills:view")
    public Result<Map<String, List<String>>> defaultBindings() {
        return Result.success(skillRegistry.defaultBindings());
    }

    /** 按技能分组全部绑定关系（技能清单页展示"绑定了哪些智能体"用） */
    private Map<String, List<String>> groupBindings() {
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (AgentSkillBinding binding : agentSkillMapper.findAll()) {
            bindings.computeIfAbsent(binding.getSkillName(), key -> new ArrayList<>()).add(binding.getAgentCode());
        }
        return bindings;
    }

    /** 按智能体分组全部绑定关系（绑定编辑器回显用） */
    private Map<String, List<String>> groupBindingsByAgent() {
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (AgentSkillBinding binding : agentSkillMapper.findAll()) {
            bindings.computeIfAbsent(binding.getAgentCode(), key -> new ArrayList<>()).add(binding.getSkillName());
        }
        return bindings;
    }

    /** 解析启停参数，仅接受 0/1 */
    private Integer parseEnabled(Object value) {
        if (value == null) {
            throw new BusinessException(400, "缺少 enabled 参数");
        }
        int enabled = Integer.parseInt(String.valueOf(value));
        if (enabled != 0 && enabled != 1) {
            throw new BusinessException(400, "enabled 仅支持 0 或 1");
        }
        return enabled;
    }

    /** 从代码目录取技能描述，用于补齐 skill 表行 */
    private String skillDescription(String skillName) {
        return skillRegistry.describe(List.of(skillName)).stream()
                .findFirst()
                .map(item -> String.valueOf(item.get("description")))
                .orElse(null);
    }
}
