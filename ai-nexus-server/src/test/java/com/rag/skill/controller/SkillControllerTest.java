package com.rag.skill.controller;

import com.rag.common.BusinessException;
import com.rag.skill.core.SkillRegistry;
import com.rag.skill.entity.AgentSkillBinding;
import com.rag.skill.entity.SkillUsageStat;
import com.rag.skill.mapper.AgentSkillMapper;
import com.rag.skill.mapper.SkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能管理控制器测试：锁定两个绑定查询接口的分组形状，
 * 技能清单按"技能→智能体"分组、绑定编辑器按"智能体→技能"分组
 */
class SkillControllerTest {

    private SkillRegistry registry;
    private AgentSkillMapper agentSkillMapper;
    private SkillMapper skillMapper;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        registry = mock(SkillRegistry.class);
        agentSkillMapper = mock(AgentSkillMapper.class);
        skillMapper = mock(SkillMapper.class);
        controller = new SkillController(registry, skillMapper, agentSkillMapper);
    }

    private AgentSkillBinding binding(String agentCode, String skillName) {
        AgentSkillBinding binding = new AgentSkillBinding();
        binding.setAgentCode(agentCode);
        binding.setSkillName(skillName);
        return binding;
    }

    @Test
    void bindingsEndpointGroupsByAgent() {
        when(agentSkillMapper.findAll()).thenReturn(List.of(
                binding("general-tool", "get_weather"),
                binding("general-tool", "search_city_location")));

        Map<String, List<String>> result = controller.bindings().getData();

        assertEquals(List.of("get_weather", "search_city_location"), result.get("general-tool"));
    }

    @Test
    void skillListGroupsBindingsBySkill() {
        when(registry.describe()).thenReturn(List.of(Map.of(
                "name", "get_weather",
                "description", "查天气",
                "params", List.of())));
        when(registry.disabledSkillNames()).thenReturn(Set.of());
        when(agentSkillMapper.findAll()).thenReturn(List.of(binding("general-tool", "get_weather")));

        List<Map<String, Object>> result = controller.list().getData();

        assertEquals(List.of("general-tool"), result.get(0).get("agents"));
        assertEquals(Boolean.TRUE, result.get(0).get("enabled"));
    }

    @Test
    void saveBindingsRejectsRemovingDefaults() {
        when(registry.exists("calculate")).thenReturn(true);
        doThrow(new BusinessException(400, "默认绑定的技能不能移除：generate_image"))
                .when(registry).validateBindingChange("image-agent", List.of("calculate"));

        assertThrows(BusinessException.class,
                () -> controller.saveBindings(Map.of("agentCode", "image-agent", "skillNames", List.of("calculate"))));

        verify(agentSkillMapper, never()).deleteByAgent(anyString());
    }

    @Test
    void statsAggregatesTotalsFromSkillRows() {
        when(skillMapper.summarizeBySkill(any())).thenReturn(List.of(
                usageStat("get_weather", 8L, 7L, 1L, 100.0),
                usageStat("calculate", 2L, 2L, 0L, 50.0)));
        when(skillMapper.summarizeByAgent(any())).thenReturn(List.of());

        Map<String, Object> result = controller.stats(7).getData();

        assertEquals(7, result.get("days"));
        Map<String, Object> total = (Map<String, Object>) result.get("total");
        assertEquals(10L, total.get("calls"));
        assertEquals(9L, total.get("successCalls"));
        assertEquals(1L, total.get("failedCalls"));
        assertEquals(90.0, total.get("successRate"));
        assertEquals(90L, total.get("avgCostMs"));
        assertEquals(2, ((List<?>) result.get("skills")).size());
    }

    private SkillUsageStat usageStat(String name, long calls, long success, long failed, double avgCost) {
        SkillUsageStat stat = new SkillUsageStat();
        stat.setSkillName(name);
        stat.setCalls(calls);
        stat.setSuccessCount(success);
        stat.setFailedCount(failed);
        stat.setAvgCostMs(avgCost);
        return stat;
    }
}
