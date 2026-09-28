package com.rag.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.agent.dto.RunKnowledgeQualityRequest;
import com.rag.agent.entity.AgentRun;
import com.rag.agent.mapper.AgentRunMapper;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体管理服务
 * 负责智能体列表、运行质检、运行记录和报告详情
 */
@Service
public class AgentService {

    private static final String knowledgeQualityCode = "knowledge-quality";
    private static final String knowledgeQualityName = "知识库质检智能体";
    private static final String generalToolCode = "general-tool";
    private static final String generalToolName = "工具智能体";

    private final KnowledgeQualityAgentService knowledgeQualityAgentService;
    private final AgentRunMapper agentRunMapper;
    private final ObjectMapper objectMapper;
    private final ModelProviderService modelProviderService;

    /**
     * 创建智能体管理服务
     */
    public AgentService(KnowledgeQualityAgentService knowledgeQualityAgentService,
                        AgentRunMapper agentRunMapper,
                        ObjectMapper objectMapper,
                        ModelProviderService modelProviderService) {
        this.knowledgeQualityAgentService = knowledgeQualityAgentService;
        this.agentRunMapper = agentRunMapper;
        this.objectMapper = objectMapper;
        this.modelProviderService = modelProviderService;
    }

    /**
     * 获取智能体列表
     */
    public List<Map<String, Object>> listAgents() {
        // 第1步：读取模型设置中当前激活的模型供应商，让页面展示和实际调用保持一致
        List<Map<String, Object>> agents = new ArrayList<>();
        ModelProvider provider = modelProviderService.getActive();
        String modelDisplayName = buildModelDisplayName(provider);
        // 第2步：加入知识库质检智能体
        agents.add(buildAgent(knowledgeQualityCode, knowledgeQualityName,
                "检查知识库文件、分片、召回和问答风险，生成质量评分与优化建议。",
                "知识库上线前质检、问答效果排查、交付报告生成", provider, modelDisplayName));
        // 第3步：加入通用工具智能体
        agents.add(buildAgent(generalToolCode, generalToolName,
                "自主编排工具调用完成实时任务（当前支持天气查询），每一步执行过程实时可见。",
                "实时信息查询、多步工具编排、执行过程可视化", provider, modelDisplayName));
        // 第4步：返回智能体列表
        return agents;
    }

    /** 组装单个智能体卡片数据 */
    private Map<String, Object> buildAgent(String code, String name, String description,
                                           String scene, ModelProvider provider, String modelDisplayName) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("code", code);
        agent.put("name", name);
        agent.put("description", description);
        agent.put("status", "ENABLED");
        agent.put("version", "1.0");
        agent.put("scene", scene);
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

    /**
     * 运行知识库质检智能体
     */
    public Map<String, Object> runKnowledgeQuality(RunKnowledgeQualityRequest request, Long userId) {
        // 第1步：运行质检并生成报告
        Map<String, Object> report = knowledgeQualityAgentService.runQualityCheck(request, userId);
        // 第2步：按用户选择决定是否保存报告
        boolean saveReport = request == null || request.getSaveReport() == null || Boolean.TRUE.equals(request.getSaveReport());
        if (saveReport) {
            AgentRun agentRun = buildSuccessRun(request, report, userId);
            agentRunMapper.insert(agentRun);
            report.put("runId", agentRun.getId());
        }
        // 第3步：返回报告
        return report;
    }

    /**
     * 查询智能体运行历史
     */
    public List<AgentRun> listRuns(Long userId, Integer limit) {
        // 第1步：限制查询数量
        int safeLimit = limit == null || limit <= 0 || limit > 100 ? 30 : limit;
        // 第2步：查询当前用户的运行记录
        return agentRunMapper.findRecent(userId, safeLimit);
    }

    /**
     * 查询智能体运行详情
     */
    public Map<String, Object> getRunDetail(Long id, Long userId) {
        // 第1步：查询运行记录
        AgentRun agentRun = agentRunMapper.findById(id);
        if (agentRun == null || !userId.equals(agentRun.getCreatedBy())) {
            throw new BusinessException(404, "智能体运行记录不存在");
        }
        // 第2步：组装详情对象
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("run", agentRun);
        detail.put("report", parseJson(agentRun.getReportJson()));
        detail.put("request", parseJson(agentRun.getRequestJson()));
        // 第3步：返回运行详情
        return detail;
    }

    /**
     * 删除智能体运行记录
     */
    public void deleteRun(Long id, Long userId) {
        // 第1步：删除当前用户自己的记录
        int deleted = agentRunMapper.deleteById(id, userId);
        // 第2步：删除失败时提示记录不存在
        if (deleted == 0) {
            throw new BusinessException(404, "智能体运行记录不存在");
        }
    }

    /**
     * 构建成功运行记录
     */
    private AgentRun buildSuccessRun(RunKnowledgeQualityRequest request, Map<String, Object> report, Long userId) {
        // 第1步：创建运行记录对象
        AgentRun agentRun = new AgentRun();
        // 第2步：写入智能体基础信息
        agentRun.setAgentCode(knowledgeQualityCode);
        agentRun.setAgentName(knowledgeQualityName);
        agentRun.setRunStatus("SUCCESS");
        // 第3步：写入知识库和评分信息
        agentRun.setKbId(toLong(report.get("kbId")));
        agentRun.setKbName(toStringValue(report.get("kbName")));
        agentRun.setCheckMode(toStringValue(report.get("checkMode")));
        agentRun.setScore(toInt(report.get("score")));
        agentRun.setRiskLevel(toStringValue(report.get("riskLevel")));
        agentRun.setSummary(toStringValue(report.get("summary")));
        // 第4步：写入 JSON 信息
        agentRun.setRequestJson(writeJson(request));
        agentRun.setReportJson(writeJson(report));
        agentRun.setCreatedBy(userId);
        return agentRun;
    }

    /**
     * 将对象写成 JSON 字符串
     */
    private String writeJson(Object value) {
        // 第1步：使用 Jackson 序列化对象
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 第2步：序列化失败时抛出业务异常
            throw new BusinessException("智能体报告序列化失败：" + e.getMessage());
        }
    }

    /**
     * 解析 JSON 字符串
     */
    private Object parseJson(String json) {
        // 第1步：空 JSON 返回空对象
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        // 第2步：读取 JSON 内容
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            // 第3步：解析失败时返回原文，避免详情页完全不可用
            return json;
        }
    }

    /**
     * 转换成长整数
     */
    private Long toLong(Object value) {
        // 第1步：数字类型直接转换
        if (value instanceof Number number) {
            return number.longValue();
        }
        // 第2步：其他类型返回空
        return null;
    }

    /**
     * 转换成整数
     */
    private Integer toInt(Object value) {
        // 第1步：数字类型直接转换
        if (value instanceof Number number) {
            return number.intValue();
        }
        // 第2步：其他类型返回 0
        return 0;
    }

    /**
     * 转换成字符串
     */
    private String toStringValue(Object value) {
        // 第1步：空值返回空字符串
        if (value == null) {
            return "";
        }
        // 第2步：普通对象转换为字符串
        return value.toString();
    }
}
