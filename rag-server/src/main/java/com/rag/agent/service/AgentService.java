package com.rag.agent.service;

import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体管理服务
 * 负责智能体总览列表数据
 */
@Service
public class AgentService {

    private static final String generalToolCode = "general-tool";
    private static final String generalToolName = "天气查询智能体";

    private final ModelProviderService modelProviderService;

    /**
     * 创建智能体管理服务
     */
    public AgentService(ModelProviderService modelProviderService) {
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
        // 第2步：加入天气查询智能体
        agents.add(buildAgent(generalToolCode, generalToolName,
                "调用天气定位与查询工具回答实时天气问题，每一步执行过程实时可见。",
                "实时天气查询、坐标定位、执行过程可视化", provider, modelDisplayName));
        // 第3步：返回智能体列表
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
}
