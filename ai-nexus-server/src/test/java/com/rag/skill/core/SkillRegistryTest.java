package com.rag.skill.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.BusinessException;
import com.rag.skill.entity.SkillInfo;
import com.rag.skill.mapper.AgentSkillMapper;
import com.rag.skill.mapper.SkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能注册中心测试：验证绑定解析、停用过滤、执行拦截和用户注入（不依赖真实数据库）
 */
class SkillRegistryTest {

    private SkillMapper skillMapper;
    private AgentSkillMapper agentSkillMapper;

    /** 记录最近一次收到的参数，用于断言注入结果 */
    private static JsonNode lastArguments;

    /** 只用于注册的空技能 */
    static class NoopTool implements AgentTool {
        @Override
        public String name() {
            return "noop";
        }

        @Override
        public String description() {
            return "空技能";
        }

        @Override
        public List<ToolParam> params() {
            return List.of();
        }

        @Override
        public String execute(JsonNode arguments) {
            lastArguments = arguments;
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        skillMapper = mock(SkillMapper.class);
        agentSkillMapper = mock(AgentSkillMapper.class);
        lastArguments = null;
    }

    private SkillInfo skill(String name, Integer enabled) {
        SkillInfo info = new SkillInfo();
        info.setSkillName(name);
        info.setEnabled(enabled);
        return info;
    }

    private SkillRegistry newRegistry() {
        return new SkillRegistry(List.of(new NoopTool()), new ObjectMapper(), skillMapper, agentSkillMapper, Runnable::run, 120000);
    }

    @Test
    void executeTimesOutForSlowSkill() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            AgentTool slowTool = new AgentTool() {
                @Override
                public String name() {
                    return "slow";
                }

                @Override
                public String description() {
                    return "慢技能";
                }

                @Override
                public List<ToolParam> params() {
                    return List.of();
                }

                @Override
                public String execute(JsonNode arguments) throws InterruptedException {
                    Thread.sleep(2000);
                    return "done";
                }
            };
            SkillRegistry registry = new SkillRegistry(List.of(slowTool), new ObjectMapper(), skillMapper, agentSkillMapper, executor, 200);
            when(skillMapper.findAll()).thenReturn(List.of());
            String result = registry.execute("slow", "{}", 1L);
            assertTrue(result.contains("执行超时"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bindingFiltersDisabledSkills() {
        when(agentSkillMapper.findSkillNamesByAgent("demo-agent")).thenReturn(List.of("noop"));
        when(skillMapper.findAll()).thenReturn(List.of(skill("noop", 0)));
        assertTrue(newRegistry().skillsForAgent("demo-agent").isEmpty());
    }

    @Test
    void bindingReturnsEnabledSkills() {
        when(agentSkillMapper.findSkillNamesByAgent("demo-agent")).thenReturn(List.of("noop"));
        when(skillMapper.findAll()).thenReturn(List.of(skill("noop", 1)));
        assertEquals(List.of("noop"), newRegistry().skillsForAgent("demo-agent"));
    }

    @Test
    void unboundAgentGetsNoSkills() {
        when(agentSkillMapper.findSkillNamesByAgent("fresh-agent")).thenReturn(List.of());
        assertTrue(newRegistry().skillsForAgent("fresh-agent").isEmpty());
    }

    @Test
    void emptyBindingExposesNoSkillsToModel() {
        // 空绑定/全部停用时必须一个技能都不暴露，而不是退化为全部暴露
        assertTrue(newRegistry().openAiTools(List.of()).isEmpty());
        assertTrue(newRegistry().describe(List.of()).isEmpty());
    }

    @Test
    void databaseFailureFallsBackToLegacyBindings() {
        when(agentSkillMapper.findSkillNamesByAgent("general-tool")).thenThrow(new RuntimeException("db down"));
        assertEquals(List.of("search_city_location", "get_weather"), newRegistry().skillsForAgent("general-tool"));
    }

    @Test
    void executeRejectsDisabledSkill() throws Exception {
        when(skillMapper.findAll()).thenReturn(List.of(skill("noop", 0)));
        String result = newRegistry().execute("noop", "{}", 1L);
        assertTrue(result.contains("已停用"));
    }

    @Test
    void executeInjectsUserId() throws Exception {
        when(skillMapper.findAll()).thenReturn(List.of());
        String result = newRegistry().execute("noop", "{\"text\":\"你好\"}", 42L);
        assertEquals("ok", result);
        assertEquals(42L, lastArguments.path("userId").asLong());
        assertEquals("你好", lastArguments.path("text").asText());
    }

    @Test
    void provisionsDefaultBindingsForUnboundAgents() {
        when(agentSkillMapper.findSkillNamesByAgent("general-tool")).thenReturn(List.of());
        when(agentSkillMapper.findSkillNamesByAgent("image-agent")).thenReturn(List.of("generate_image"));
        newRegistry().provisionDefaultBindings();
        verify(agentSkillMapper).insertBatch("general-tool", List.of("search_city_location", "get_weather"));
        verify(agentSkillMapper, never()).insertBatch(eq("image-agent"), anyList());
    }

    @Test
    void provisioningIsSkippedWhenDatabaseUnavailable() {
        when(agentSkillMapper.findSkillNamesByAgent(anyString())).thenThrow(new RuntimeException("db down"));
        newRegistry().provisionDefaultBindings();
        verify(agentSkillMapper, never()).insertBatch(anyString(), anyList());
    }

    @Test
    void bindingChangeRejectsRemovingDefaultSkills() {
        SkillRegistry registry = newRegistry();
        // 默认技能可以保留（顺序无关），也可以新增其他技能
        registry.validateBindingChange("image-agent", new ArrayList<>(List.of("generate_image", "noop")));
        // 移除默认技能必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> registry.validateBindingChange("image-agent", List.of("noop")));
        assertTrue(exception.getMessage().contains("generate_image"));
    }
}
