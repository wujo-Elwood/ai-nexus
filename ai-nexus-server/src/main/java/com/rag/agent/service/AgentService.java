package com.rag.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.agent.entity.AgentInfo;
import com.rag.agent.entity.AgentStep;
import com.rag.agent.mapper.AgentInfoMapper;
import com.rag.agent.mapper.AgentStepMapper;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import com.rag.skill.core.SkillRegistry;
import com.rag.skill.entity.AgentSkillBinding;
import com.rag.skill.mapper.AgentSkillMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 智能体管理服务
 * 智能体卡片以 agent_info 表为准（管理页可配置），
 * 表不可用或为空时回退到代码内置定义，保证总览页可用
 */
@Slf4j
@Service
public class AgentService {

    private static final String generalToolCode = "general-tool";
    private static final String generalToolName = "天气查询智能体";
    private static final String currencyToolCode = "currency-converter";
    private static final String currencyToolName = "货币换算智能体";
    private static final String visionToolCode = "vision-agent";
    private static final String visionToolName = "视觉智能体";
    private static final String dataToolCode = "data-agent";
    private static final String dataToolName = "数据问答智能体";
    private static final String imageToolCode = "image-agent";
    private static final String imageToolName = "生图智能体";

    /** 单个智能体允许配置的最大步骤数，防止编排过深导致执行时间不可控 */
    private static final int MAX_STEPS = 20;

    /** 参数模板 JSON 校验用（只做格式检查，不参与业务逻辑） */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ModelProviderService modelProviderService;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentStepMapper agentStepMapper;
    private final SkillRegistry skillRegistry;

    /**
     * 创建智能体管理服务
     */
    public AgentService(ModelProviderService modelProviderService,
                        AgentSkillMapper agentSkillMapper,
                        AgentInfoMapper agentInfoMapper,
                        AgentStepMapper agentStepMapper,
                        SkillRegistry skillRegistry) {
        this.modelProviderService = modelProviderService;
        this.agentSkillMapper = agentSkillMapper;
        this.agentInfoMapper = agentInfoMapper;
        this.agentStepMapper = agentStepMapper;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 获取智能体列表（登录用户视角）：内置启用智能体 + 本人创建的自定义智能体
     */
    public List<Map<String, Object>> listAgents(Long userId) {
        // 第1步：读取模型设置中当前激活的模型供应商与技能绑定，让页面展示和实际调用保持一致
        List<Map<String, Object>> agents = new ArrayList<>();
        ModelProvider provider = modelProviderService.getActive();
        String modelDisplayName = buildModelDisplayName(provider);
        Map<String, List<String>> skillBindings = querySkillBindings();

        // 第2步：优先读取数据库注册的智能体（内置对所有人可见，自定义仅创建人可见）
        List<AgentInfo> infos = loadAgentInfos();
        if (!infos.isEmpty()) {
            List<AgentInfo> visible = infos.stream()
                    .filter(info -> info.getCreateUser() == null
                            || (userId != null && userId.equals(info.getCreateUser())))
                    .toList();
            // 内置在前（按 sort_no），自定义在后（按创建时间）
            visible.stream()
                    .filter(info -> info.getCreateUser() == null)
                    .forEach(info -> agents.add(buildCard(info, provider, modelDisplayName, skillBindings)));
            visible.stream()
                    .filter(info -> info.getCreateUser() != null)
                    .forEach(info -> agents.add(buildCard(info, provider, modelDisplayName, skillBindings)));
            return agents;
        }

        // 第3步：数据库不可用时回退内置定义
        agents.add(buildAgent(generalToolCode, generalToolName,
                "调用天气定位与查询工具回答实时天气问题，每一步执行过程实时可见。",
                "实时天气查询、坐标定位、执行过程可视化", provider, modelDisplayName, skillBindings));
        agents.add(buildAgent(currencyToolCode, currencyToolName,
                "查询实时汇率并结合计算器完成多币种金额换算，每一步执行过程实时可见。",
                "实时汇率查询、多币种换算、执行过程可视化", provider, modelDisplayName, skillBindings));
        agents.add(buildAgent(visionToolCode, visionToolName,
                "上传图片进行识别与分析（截图、照片中的文字与物体），结合计算工具完成图中金额合计等多步分析。",
                "图片识别、文字提取、结合计算的多步分析", provider, modelDisplayName, skillBindings));
        agents.add(buildAgent(dataToolCode, dataToolName,
                "只读查询系统数据库，回答用户、知识库、调用量等数据统计问题，每一步 SQL 与结果实时可见。",
                "数据统计、业务问答、只读查询、SQL 过程可视化", provider, modelDisplayName, skillBindings));
        Map<String, Object> imageAgent = buildAgent(imageToolCode, imageToolName,
                "根据对话内容整理提示词并调用生图工具生成 AI 图片，出图使用模型设置中激活的生图供应商，结果保存到 AI 生图历史，每一步执行过程实时可见。",
                "AI 图片生成、提示词整理、执行过程可视化", provider, modelDisplayName, skillBindings);
        enrichImageAgent(imageAgent, provider);
        agents.add(imageAgent);
        return agents;
    }

    /** 组装注册表智能体卡片：自定义智能体打上 custom 标记 */
    private Map<String, Object> buildCard(AgentInfo info, ModelProvider provider,
                                          String modelDisplayName, Map<String, List<String>> skillBindings) {
        Map<String, Object> card = buildAgent(info.getAgentCode(), info.getAgentName(),
                info.getDescription(), info.getScene() == null || info.getScene().isBlank()
                        ? (info.getCreateUser() != null ? "自定义智能体" : "平台智能体") : info.getScene(),
                provider, modelDisplayName, skillBindings);
        card.put("custom", info.getCreateUser() != null);
        card.put("enabled", info.getEnabled());
        if (imageToolCode.equals(info.getAgentCode())) {
            enrichImageAgent(card, provider);
        }
        return card;
    }

    /** 读取启用的智能体注册信息；表不可用时返回空列表，由调用方回退内置定义 */
    private List<AgentInfo> loadAgentInfos() {
        try {
            List<AgentInfo> infos = agentInfoMapper.findAllEnabled();
            return infos == null ? List.of() : infos;
        } catch (Exception e) {
            log.warn("读取智能体注册表失败，回退内置定义: {}", e.getMessage());
            return List.of();
        }
    }

    /** 生图智能体专属展示信息：出图供应商激活状态与实际生图模型 */
    private void enrichImageAgent(Map<String, Object> card, ModelProvider provider) {
        ModelProvider imageProvider = modelProviderService.findImageActiveProvider();
        card.put("imageProviderActive", imageProvider != null);
        card.put("imageModelDisplayName", buildImageModelDisplayName(imageProvider != null ? imageProvider : provider));
    }

    /** 读取全部智能体的技能绑定，按智能体编码分组；查询失败时返回空映射 */
    private Map<String, List<String>> querySkillBindings() {
        try {
            Map<String, List<String>> bindings = new LinkedHashMap<>();
            for (AgentSkillBinding binding : agentSkillMapper.findAll()) {
                bindings.computeIfAbsent(binding.getAgentCode(), key -> new ArrayList<>()).add(binding.getSkillName());
            }
            return bindings;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 构建生图模型展示名：与 ImageGenerateService 的取值逻辑一致，
     * 优先供应商配置的专用生图模型（imageModel），未配置时回退其对话模型
     */
    private String buildImageModelDisplayName(ModelProvider provider) {
        if (provider == null) {
            return "未配置模型";
        }
        String model = provider.getImageModel() != null && !provider.getImageModel().isBlank()
                ? provider.getImageModel().trim() : provider.getModel();
        String name = provider.getName() == null ? "" : provider.getName().trim();
        if (name.isEmpty()) {
            return model == null || model.isEmpty() ? "未配置模型" : model;
        }
        if (model == null || model.isEmpty()) {
            return name;
        }
        return name + " / " + model;
    }

    /** 组装单个智能体卡片数据，附上该智能体绑定的技能列表 */
    private Map<String, Object> buildAgent(String code, String name, String description,
                                           String scene, ModelProvider provider, String modelDisplayName,
                                           Map<String, List<String>> skillBindings) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("code", code);
        agent.put("name", name);
        agent.put("description", description);
        agent.put("status", "ENABLED");
        agent.put("version", "1.0");
        agent.put("scene", scene);
        agent.put("skills", skillBindings.getOrDefault(code, List.of()));
        agent.put("providerId", provider == null ? null : provider.getId());
        agent.put("providerName", provider == null ? null : provider.getName());
        agent.put("modelName", provider == null ? null : provider.getModel());
        agent.put("modelDisplayName", modelDisplayName);
        return agent;
    }

    /**
     * 构建智能体卡片展示的模型名称
     */
    private String buildModelDisplayName(ModelProvider provider) {
        // 第1步：没有激活模型时返回未配置，避免前端显示假模型名称
        if (provider == null) {
            return "未配置模型";
        }
        // 第2步：供应商或模型为空时只展示已有的信息
        String providerName = provider.getName() == null ? "" : provider.getName().trim();
        String modelName = provider.getModel() == null ? "" : provider.getModel().trim();
        if (providerName.isEmpty()) {
            return modelName.isEmpty() ? "未配置模型" : modelName;
        }
        if (modelName.isEmpty()) {
            return providerName;
        }
        // 第3步：供应商和模型都存在时按“供应商 / 模型”展示
        return providerName + " / " + modelName;
    }

    // ————————————————— 自定义智能体（按创建人隔离） —————————————————

    /** 创建自定义智能体：生成唯一编码，落库注册表并写入技能绑定与步骤编排 */
    @Transactional
    public Map<String, Object> createCustom(Long userId, String name, String description,
                                            String scene, String systemPrompt, List<String> skills,
                                            List<Map<String, Object>> steps) {
        // 第1步：校验名称，生成不冲突的编码
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new BusinessException(400, "智能体名称不能为空");
        }
        if (cleanName.length() > 64) {
            throw new BusinessException(400, "智能体名称最长 64 个字");
        }
        String code = "custom-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
        // 第2步：校验并保存技能绑定（先校验后落库，绑定失败即整体失败）
        List<String> cleanSkills = validateSkills(skills);
        List<AgentStep> cleanSteps = parseSteps(code, steps);
        AgentInfo agent = new AgentInfo();
        agent.setAgentCode(code);
        agent.setAgentName(cleanName);
        agent.setDescription(description == null || description.isBlank() ? null : description.trim());
        agent.setScene(scene == null || scene.isBlank() ? "自定义智能体" : scene.trim());
        agent.setSystemPrompt(systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt.trim());
        agent.setEnabled(1);
        agent.setBuiltIn(0);
        agent.setSortNo(100);
        agent.setCreateUser(userId);
        agentInfoMapper.insert(agent);
        saveBindings(code, cleanSkills);
        saveSteps(code, cleanSteps);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentCode", code);
        result.put("skills", cleanSkills);
        result.put("steps", toStepViews(cleanSteps));
        return result;
    }

    /** 编辑自定义智能体（仅创建人）：更新基础信息并全量替换技能绑定与步骤编排 */
    @Transactional
    public Map<String, Object> updateCustom(Long userId, String code, String name, String description,
                                            String scene, String systemPrompt, List<String> skills,
                                            List<Map<String, Object>> steps) {
        // 第1步：校验归属
        AgentInfo agent = requireOwned(userId, code);
        // 第2步：更新基础信息
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new BusinessException(400, "智能体名称不能为空");
        }
        agent.setAgentName(cleanName);
        agent.setDescription(description == null || description.isBlank() ? null : description.trim());
        agent.setScene(scene == null || scene.isBlank() ? "自定义智能体" : scene.trim());
        agent.setSystemPrompt(systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt.trim());
        agentInfoMapper.update(agent);
        // 第3步：全量替换技能绑定
        List<String> cleanSkills = validateSkills(skills);
        saveBindings(code, cleanSkills);
        // 第4步：步骤编排同样是全量替换；未传该字段时保持原有步骤不变，避免旧前端误清空
        List<AgentStep> cleanSteps = null;
        if (steps != null) {
            cleanSteps = parseSteps(code, steps);
            saveSteps(code, cleanSteps);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentCode", code);
        result.put("skills", cleanSkills);
        result.put("steps", cleanSteps == null ? toStepViews(agentStepMapper.findByAgent(code)) : toStepViews(cleanSteps));
        return result;
    }

    /** 删除自定义智能体（仅创建人）：删除注册信息、技能绑定与步骤编排 */
    @Transactional
    public void deleteCustom(Long userId, String code) {
        requireOwned(userId, code);
        agentInfoMapper.deleteByCode(code);
        agentSkillMapper.deleteByAgent(code);
        agentStepMapper.deleteByAgent(code);
    }

    /** 自定义智能体详情（仅创建人）：基础信息 + 绑定技能 + 步骤编排，编排对话框回显用 */
    public Map<String, Object> customDetail(Long userId, String code) {
        AgentInfo agent = requireOwned(userId, code);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("agentCode", agent.getAgentCode());
        detail.put("name", agent.getAgentName());
        detail.put("description", agent.getDescription());
        detail.put("scene", agent.getScene());
        detail.put("systemPrompt", agent.getSystemPrompt());
        detail.put("enabled", agent.getEnabled());
        detail.put("skills", agentSkillMapper.findSkillNamesByAgent(code));
        detail.put("steps", toStepViews(agentStepMapper.findByAgent(code)));
        return detail;
    }

    /** 普通用户可绑定的技能清单：代码目录内已启用的技能（名称 + 说明） */
    public List<Map<String, Object>> availableSkills() {
        Set<String> disabled = skillRegistry.disabledSkillNames();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : skillRegistry.describe()) {
            String name = String.valueOf(item.get("name"));
            if (disabled.contains(name)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("description", item.get("description"));
            result.add(entry);
        }
        return result;
    }

    /** 校验归属：智能体存在且为当前用户创建，否则抛业务异常 */
    private AgentInfo requireOwned(Long userId, String code) {
        AgentInfo agent = agentInfoMapper.findByCode(code);
        if (agent == null) {
            throw new BusinessException(404, "智能体不存在：" + code);
        }
        if (agent.getCreateUser() == null || !agent.getCreateUser().equals(userId)) {
            throw new BusinessException(403, "只能操作自己创建的智能体");
        }
        return agent;
    }

    /** 校验技能清单：必须存在于代码目录且处于启用状态 */
    private List<String> validateSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        Set<String> disabled = skillRegistry.disabledSkillNames();
        List<String> cleaned = skills.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        for (String skill : cleaned) {
            if (!skillRegistry.exists(skill)) {
                throw new BusinessException(400, "技能不存在：" + skill);
            }
            if (disabled.contains(skill)) {
                throw new BusinessException(400, "技能已停用：" + skill);
            }
        }
        return cleaned;
    }

    // ————————————————— 步骤编排（TOOL / LLM / ANSWER） —————————————————

    /**
     * 解析并校验前端提交的步骤编排：
     * 步骤序号按数组顺序从 1 重新编号；TOOL 步骤必须有可用技能，LLM/ANSWER 步骤必须有指令；
     * 参数模板必须是 JSON 对象（占位符写在字符串内，如 {"city": "${input}"}）
     */
    private List<AgentStep> parseSteps(String agentCode, List<Map<String, Object>> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            return List.of();
        }
        if (rawSteps.size() > MAX_STEPS) {
            throw new BusinessException(400, "单个智能体最多配置 " + MAX_STEPS + " 个步骤");
        }
        Set<String> disabled = skillRegistry.disabledSkillNames();
        List<AgentStep> steps = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> raw : rawSteps) {
            if (raw == null) {
                continue;
            }
            index++;
            String type = text(raw.get("stepType"), AgentStep.TYPE_TOOL).toUpperCase();
            if (!AgentStep.TYPE_TOOL.equals(type)
                    && !AgentStep.TYPE_LLM.equals(type)
                    && !AgentStep.TYPE_ANSWER.equals(type)) {
                throw new BusinessException(400, "第 " + index + " 步的类型不支持：" + type);
            }
            String toolName = text(raw.get("toolName"), null);
            String instruction = text(raw.get("instruction"), null);
            String argsTemplate = text(raw.get("argsTemplate"), null);
            if (AgentStep.TYPE_TOOL.equals(type)) {
                if (toolName == null) {
                    throw new BusinessException(400, "第 " + index + " 步还没有选择技能");
                }
                if (!skillRegistry.exists(toolName)) {
                    throw new BusinessException(400, "第 " + index + " 步的技能不存在：" + toolName);
                }
                if (disabled.contains(toolName)) {
                    throw new BusinessException(400, "第 " + index + " 步的技能已停用：" + toolName);
                }
                validateArgsTemplate(argsTemplate, index);
            } else {
                if (instruction == null) {
                    throw new BusinessException(400, "第 " + index + " 步还没有填写指令");
                }
                toolName = null;
                argsTemplate = null;
            }
            AgentStep step = new AgentStep();
            step.setAgentCode(agentCode);
            step.setStepNo(index);
            step.setStepName(text(raw.get("stepName"), null));
            step.setStepType(type);
            step.setToolName(toolName);
            step.setArgsTemplate(argsTemplate);
            step.setInstruction(instruction);
            step.setEnabled(enabledFlag(raw.get("enabled")));
            steps.add(step);
        }
        return steps;
    }

    /** 参数模板校验：必须是 JSON 对象，否则执行时无法可靠注入占位符 */
    private void validateArgsTemplate(String argsTemplate, int index) {
        if (argsTemplate == null) {
            return;
        }
        try {
            JsonNode node = JSON_MAPPER.readTree(argsTemplate);
            if (node == null || !node.isObject()) {
                throw new BusinessException(400, "第 " + index + " 步的参数模板必须是 JSON 对象");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "第 " + index + " 步的参数模板不是合法 JSON：" + e.getMessage());
        }
    }

    /** 全量替换智能体的步骤编排 */
    private void saveSteps(String code, List<AgentStep> steps) {
        agentStepMapper.deleteByAgent(code);
        if (steps != null && !steps.isEmpty()) {
            agentStepMapper.insertBatch(steps);
        }
    }

    /** 步骤定义转前端视图结构 */
    private List<Map<String, Object>> toStepViews(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (AgentStep step : steps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stepNo", step.getStepNo());
            view.put("stepName", step.getStepName());
            view.put("stepType", step.getStepType());
            view.put("toolName", step.getToolName());
            view.put("argsTemplate", step.getArgsTemplate());
            view.put("instruction", step.getInstruction());
            view.put("enabled", step.getEnabled() == null ? 1 : step.getEnabled());
            views.add(view);
        }
        return views;
    }

    /** 读取文本字段，空白视为未填写 */
    private String text(Object raw, String defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? defaultValue : value;
    }

    /** 读取启用标记：缺省为启用 */
    private Integer enabledFlag(Object raw) {
        if (raw == null) {
            return 1;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return 1;
        }
        return "0".equals(value) || "false".equalsIgnoreCase(value) ? 0 : 1;
    }

    /** 全量替换智能体的技能绑定 */
    private void saveBindings(String code, List<String> skills) {
        agentSkillMapper.deleteByAgent(code);
        if (!skills.isEmpty()) {
            agentSkillMapper.insertBatch(code, skills);
        }
    }
}
