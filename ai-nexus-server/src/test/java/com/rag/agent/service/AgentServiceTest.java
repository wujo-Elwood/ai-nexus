package com.rag.agent.service;

import com.rag.agent.entity.AgentInfo;
import com.rag.agent.mapper.AgentInfoMapper;
import com.rag.agent.mapper.AgentStepMapper;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import com.rag.skill.core.SkillRegistry;
import com.rag.skill.entity.AgentSkillBinding;
import com.rag.skill.mapper.AgentSkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 智能体管理服务测试：验证 DB 驱动卡片、绑定技能展示与注册表不可用回退
 */
class AgentServiceTest {

    private ModelProviderService modelProviderService;
    private AgentSkillMapper agentSkillMapper;
    private AgentInfoMapper agentInfoMapper;
    private AgentStepMapper agentStepMapper;
    private SkillRegistry skillRegistry;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        modelProviderService = mock(ModelProviderService.class);
        agentSkillMapper = mock(AgentSkillMapper.class);
        agentInfoMapper = mock(AgentInfoMapper.class);
        agentStepMapper = mock(AgentStepMapper.class);
        skillRegistry = mock(SkillRegistry.class);
        agentService = new AgentService(modelProviderService, agentSkillMapper, agentInfoMapper,
                agentStepMapper, skillRegistry);
        when(modelProviderService.getActive()).thenReturn(null);
    }

    private AgentInfo info(String code, String name) {
        AgentInfo info = new AgentInfo();
        info.setAgentCode(code);
        info.setAgentName(name);
        info.setDescription("描述");
        info.setScene("场景");
        info.setEnabled(1);
        return info;
    }

    private AgentSkillBinding binding(String agentCode, String skillName) {
        AgentSkillBinding binding = new AgentSkillBinding();
        binding.setAgentCode(agentCode);
        binding.setSkillName(skillName);
        return binding;
    }

    @Test
    void rendersCardsFromRegistry() {
        when(agentInfoMapper.findAllEnabled()).thenReturn(List.of(info("kb-helper", "知识库助手")));
        when(agentSkillMapper.findAll()).thenReturn(List.of(binding("kb-helper", "search_knowledge_base")));

        List<Map<String, Object>> agents = agentService.listAgents(14L);

        assertEquals(1, agents.size());
        Map<String, Object> card = agents.get(0);
        assertEquals("kb-helper", card.get("code"));
        assertEquals("知识库助手", card.get("name"));
        assertEquals(List.of("search_knowledge_base"), card.get("skills"));
    }

    @Test
    void imageAgentCardCarriesImageModelInfo() {
        when(agentInfoMapper.findAllEnabled()).thenReturn(List.of(info("image-agent", "生图智能体")));
        when(agentSkillMapper.findAll()).thenReturn(List.of());

        Map<String, Object> card = agentService.listAgents(14L).get(0);
        assertEquals(Boolean.FALSE, card.get("imageProviderActive"));
        assertEquals("未配置模型", card.get("imageModelDisplayName"));
    }

    @Test
    void fallsBackToBuiltinWhenRegistryUnavailable() {
        when(agentInfoMapper.findAllEnabled()).thenThrow(new RuntimeException("db down"));
        when(agentSkillMapper.findAll()).thenReturn(List.of());

        List<Map<String, Object>> agents = agentService.listAgents(14L);

        assertEquals(5, agents.size());
        assertEquals("general-tool", agents.get(0).get("code"));
        assertEquals("image-agent", agents.get(4).get("code"));
    }

    @Test
    void customAgentsVisibleOnlyToOwner() {
        AgentInfo mine = info("custom-14-ab12cd34", "我的助手");
        mine.setCreateUser(14L);
        AgentInfo others = info("custom-99-ffff0000", "别人的助手");
        others.setCreateUser(99L);
        when(agentInfoMapper.findAllEnabled()).thenReturn(List.of(info("general-tool", "天气查询智能体"), mine, others));
        when(agentSkillMapper.findAll()).thenReturn(List.of());

        List<Map<String, Object>> agents = agentService.listAgents(14L);

        // 内置 + 自己的自定义；他人的自定义不可见
        assertEquals(2, agents.size());
        assertEquals("general-tool", agents.get(0).get("code"));
        assertEquals("custom-14-ab12cd34", agents.get(1).get("code"));
        assertEquals(Boolean.TRUE, agents.get(1).get("custom"));
    }
}
