package com.rag.agent.controller;

import com.rag.agent.entity.AgentInfo;
import com.rag.agent.mapper.AgentInfoMapper;
import com.rag.agent.mapper.AgentStepMapper;
import com.rag.agent.service.AgentService;
import com.rag.common.BusinessException;
import com.rag.rbac.annotation.RequirePermission;
import com.rag.skill.mapper.AgentSkillMapper;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智能体管理控制器
 * 提供智能体总览列表接口；管理端点支持新建、编辑、启停和删除（仅自定义智能体）
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    /** 智能体编码规则：小写字母/数字开头，允许连字符，2-64 位 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    private final AgentService agentService;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentStepMapper agentStepMapper;

    public AgentController(AgentService agentService,
                           AgentInfoMapper agentInfoMapper,
                           AgentSkillMapper agentSkillMapper,
                           AgentStepMapper agentStepMapper) {
        this.agentService = agentService;
        this.agentInfoMapper = agentInfoMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentStepMapper = agentStepMapper;
    }

    /**
     * 获取智能体列表（登录用户视角：内置 + 本人创建的自定义智能体）
     */
    @GetMapping
    public Result<List<Map<String, Object>>> listAgents(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(agentService.listAgents(userId));
    }

    /**
     * 当前用户可绑定的技能清单（代码目录内已启用的技能）
     */
    @GetMapping("/available-skills")
    public Result<List<Map<String, Object>>> availableSkills() {
        return Result.success(agentService.availableSkills());
    }

    /**
     * 自定义智能体详情（仅创建人）：编排对话框回显
     */
    @GetMapping("/custom/{code}")
    public Result<Map<String, Object>> customDetail(@PathVariable String code, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(agentService.customDetail(userId, code));
    }

    /**
     * 创建自定义智能体（登录用户即可，按创建人隔离）
     */
    @PostMapping("/custom")
    public Result<Map<String, Object>> createCustom(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return Result.success(agentService.createCustom(userId,
                stringField(body, "name", null),
                stringField(body, "description", null),
                stringField(body, "scene", null),
                stringField(body, "systemPrompt", null),
                skillList(body.get("skills")),
                stepList(body.get("steps"))));
    }

    /**
     * 编辑自定义智能体（仅创建人）
     */
    @PutMapping("/custom/{code}")
    public Result<Map<String, Object>> updateCustom(@PathVariable String code,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return Result.success(agentService.updateCustom(userId, code,
                stringField(body, "name", null),
                stringField(body, "description", null),
                stringField(body, "scene", null),
                stringField(body, "systemPrompt", null),
                skillList(body.get("skills")),
                stepList(body.get("steps"))));
    }

    /**
     * 删除自定义智能体（仅创建人）
     */
    @DeleteMapping("/custom/{code}")
    public Result<Void> deleteCustom(@PathVariable String code, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        agentService.deleteCustom(userId, code);
        return Result.success();
    }

    /** 读取字符串字段，空白时回退默认值 */
    private String stringField(Map<String, Object> body, String key, String defaultValue) {
        String value = body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? defaultValue : value;
    }

    /** 读取技能编码列表字段（前端传字符串数组） */
    private List<String> skillList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * 读取步骤编排字段（前端传对象数组）
     * 没传该字段时返回 null，表示「保持原有步骤不变」，避免旧版前端保存时误清空
     */
    private List<Map<String, Object>> stepList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> step = new LinkedHashMap<>();
                map.forEach((key, value) -> step.put(String.valueOf(key), value));
                steps.add(step);
            }
        }
        return steps;
    }

    /**
     * 管理列表：全部智能体（含停用）
     */
    @GetMapping("/manage")
    @RequirePermission("skills:view")
    public Result<List<AgentInfo>> manageList() {
        return Result.success(agentInfoMapper.findAll());
    }

    /**
     * 新建自定义智能体
     */
    @PostMapping("/manage")
    @RequirePermission("skills:view")
    public Result<Void> create(@RequestBody Map<String, Object> body) {
        // 第1步：解析并校验编码与名称
        String code = body.get("code") == null ? "" : String.valueOf(body.get("code")).trim();
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException(400, "智能体编码需为 2-64 位小写字母、数字或连字符");
        }
        if (name.isEmpty()) {
            throw new BusinessException(400, "智能体名称不能为空");
        }
        if (agentInfoMapper.findByCode(code) != null) {
            throw new BusinessException(400, "智能体编码已存在：" + code);
        }
        // 第2步：入库（自定义智能体不设默认绑定，技能由绑定接口配置）
        AgentInfo agent = new AgentInfo();
        agent.setAgentCode(code);
        agent.setAgentName(name);
        agent.setDescription(stringField(body, "description", null));
        agent.setScene(stringField(body, "scene", "平台智能体"));
        agent.setSystemPrompt(stringField(body, "systemPrompt", null));
        agent.setEnabled(1);
        agent.setBuiltIn(0);
        agent.setSortNo(100);
        agentInfoMapper.insert(agent);
        return Result.success();
    }

    /**
     * 编辑智能体基础信息（内置智能体也可编辑提示词等）
     */
    @PutMapping("/manage/{code}")
    @RequirePermission("skills:view")
    public Result<Void> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        // 第1步：校验智能体存在
        AgentInfo agent = agentInfoMapper.findByCode(code);
        if (agent == null) {
            throw new BusinessException(404, "智能体不存在：" + code);
        }
        String name = body.get("name") == null ? agent.getAgentName() : String.valueOf(body.get("name")).trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "智能体名称不能为空");
        }
        // 第2步：更新基础信息（编码不可改）
        AgentInfo update = new AgentInfo();
        update.setAgentCode(code);
        update.setAgentName(name);
        update.setDescription(stringField(body, "description", agent.getDescription()));
        update.setScene(stringField(body, "scene", agent.getScene()));
        update.setSystemPrompt(stringField(body, "systemPrompt", agent.getSystemPrompt()));
        agentInfoMapper.update(update);
        return Result.success();
    }

    /**
     * 启用或停用智能体
     */
    @PutMapping("/manage/{code}/enabled")
    @RequirePermission("skills:view")
    public Result<Void> updateEnabled(@PathVariable String code, @RequestBody Map<String, Object> body) {
        if (agentInfoMapper.findByCode(code) == null) {
            throw new BusinessException(404, "智能体不存在：" + code);
        }
        int enabled = body.get("enabled") == null ? 1 : Integer.parseInt(String.valueOf(body.get("enabled")));
        if (enabled != 0 && enabled != 1) {
            throw new BusinessException(400, "enabled 仅支持 0 或 1");
        }
        agentInfoMapper.updateEnabled(code, enabled);
        return Result.success();
    }

    /**
     * 删除自定义智能体（内置智能体不可删除）
     */
    @DeleteMapping("/manage/{code}")
    @RequirePermission("skills:view")
    @Transactional
    public Result<Void> delete(@PathVariable String code) {
        // 第1步：校验存在且非内置
        AgentInfo agent = agentInfoMapper.findByCode(code);
        if (agent == null) {
            throw new BusinessException(404, "智能体不存在：" + code);
        }
        if (agent.getBuiltIn() != null && agent.getBuiltIn() == 1) {
            throw new BusinessException(400, "内置智能体不能删除，可改为停用");
        }
        // 第2步：删除注册信息、技能绑定与步骤编排
        agentInfoMapper.deleteByCode(code);
        agentSkillMapper.deleteByAgent(code);
        agentStepMapper.deleteByAgent(code);
        return Result.success();
    }
}
