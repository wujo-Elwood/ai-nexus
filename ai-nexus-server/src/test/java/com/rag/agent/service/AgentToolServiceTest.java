package com.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.agent.dto.AgentToolChatRequest;
import com.rag.agent.entity.AgentStep;
import com.rag.agent.entity.AgentToolStep;
import com.rag.agent.mapper.AgentToolStepMapper;
import com.rag.skill.core.AgentTool;
import com.rag.skill.core.AgentTool.ToolParam;
import com.rag.skill.core.SkillRegistry;
import com.rag.skill.mapper.AgentSkillMapper;
import com.rag.skill.mapper.SkillMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 通用工具智能体流式执行循环测试：用脚本化的假流式模型验证
 * 状态提示、流式增量、步骤回调、结果回填和落库
 */
class AgentToolServiceTest {

    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final AgentSkillMapper agentSkillMapper = mock(AgentSkillMapper.class);

    /** 一条脚本：先输出的增量文本 + 最终响应 */
    private record ScriptedStep(List<String> deltas, Response<AiMessage> response) {
    }

    /** 按脚本顺序回放的假流式模型 */
    private static class FakeStreamingModel implements AgentModel {
        private final List<ScriptedStep> script;
        private int calls = 0;

        FakeStreamingModel(List<ScriptedStep> script) {
            this.script = script;
        }

        @Override
        public Response<AiMessage> stream(List<ChatMessage> messages, List<Map<String, Object>> openAiTools,
                                          Consumer<String> deltaConsumer) {
            ScriptedStep step = script.get(Math.min(calls++, script.size() - 1));
            for (String delta : step.deltas()) {
                deltaConsumer.accept(delta);
            }
            return step.response();
        }
    }

    /** 只做记录的空工具 */
    private static class EchoTool implements AgentTool {
        /** 记录最近一次执行收到的 userId，用于断言框架注入 */
        static Long lastUserId;

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显输入";
        }

        @Override
        public List<ToolParam> params() {
            return List.of(new ToolParam("text", "string", "内容", true));
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            lastUserId = arguments.path("userId").asLong(0) > 0 ? arguments.path("userId").asLong() : null;
            return "回显：" + arguments.path("text").asText("");
        }
    }

    /** 必然抛错的工具：验证失败原因随 step_result 事件与落库透出 */
    private static class BoomTool implements AgentTool {
        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "总是失败";
        }

        @Override
        public List<ToolParam> params() {
            return List.of();
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            throw new IllegalStateException("数据库连接超时");
        }
    }

    /** 收集事件的监听器 */
    private static class CollectingListener implements AgentToolListener {
        final List<String> events = new ArrayList<>();
        final StringBuilder streamed = new StringBuilder();
        String answer = "";
        boolean done = false;

        @Override
        public void onOpen(String runId, Object tools) {
            events.add("open");
        }

        @Override
        public void onStatus(String message) {
            events.add("status:" + message);
        }

        @Override
        public void onStepStart(int stepNo, String toolName, String arguments) {
            events.add("step_start:" + toolName);
        }

        @Override
        public void onStepResult(int stepNo, String result, long costMs, String status, String errorMessage) {
            events.add("step_result:" + status + ":" + result
                    + (errorMessage == null || errorMessage.isEmpty() ? "" : ":" + errorMessage));
        }

        @Override
        public void onAnswerDelta(String delta) {
            events.add("delta:" + delta);
            streamed.append(delta);
        }

        @Override
        public void onAnswerReset() {
            events.add("answer_reset");
            streamed.setLength(0);
        }

        @Override
        public void onAnswer(String content) {
            events.add("answer");
            answer = content;
        }

        @Override
        public void onDone(String runId) {
            events.add("done");
            done = true;
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }
    }

    private SkillRegistry newRegistry() {
        SkillRegistry registry = new SkillRegistry(List.of(new EchoTool()), new ObjectMapper(), skillMapper, agentSkillMapper, Runnable::run, 120000);
        return registry;
    }

    private AgentToolService newService(AgentToolStepMapper stepMapper) {
        return newService(stepMapper, newRegistry());
    }

    private AgentToolService newService(AgentToolStepMapper stepMapper, SkillRegistry registry) {
        // 同线程执行器：测试中工具执行和下一轮递归按顺序同步跑完
        return new AgentToolService(registry, null, stepMapper, mock(com.rag.agent.mapper.AgentStepMapper.class),
                mock(com.rag.agent.mapper.AgentInfoMapper.class), new ObjectMapper(),
                new OpenAiCompatStreamClient(new ObjectMapper()), Runnable::run);
    }

    private List<Map<String, Object>> registryTools() {
        return new SkillRegistry(List.of(new EchoTool()), new ObjectMapper(), skillMapper, agentSkillMapper, Runnable::run, 120000).openAiTools(null);
    }

    private List<Map<String, Object>> registryToolsWith(AgentTool tool) {
        return new SkillRegistry(List.of(tool), new ObjectMapper(), skillMapper, agentSkillMapper, Runnable::run, 120000).openAiTools(null);
    }

    @Test
    void shouldExecuteToolAndStreamAnswer() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("echo")
                .arguments("{\"text\":\"你好\"}")
                .build();
        FakeStreamingModel model = new FakeStreamingModel(List.of(
                new ScriptedStep(List.of(), Response.from(AiMessage.from(request))),
                new ScriptedStep(List.of("工具结果是", "：回显：你好"),
                        Response.from(AiMessage.from("工具结果是：回显：你好")))
        ));

        AgentToolStepMapper stepMapper = mock(AgentToolStepMapper.class);
        AgentToolService service = newService(stepMapper);
        CollectingListener listener = new CollectingListener();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.systemMessage("system"),
                UserMessage.userMessage("调用工具试试")
        ));

        String answer = service.runStreamLoop(model, messages, "run-1", listener, new AtomicInteger(), registryTools(), 42L, "kb-helper");

        assertEquals("工具结果是：回显：你好", answer);
        // 工具应收到框架注入的当前用户编号
        assertEquals(42L, EchoTool.lastUserId);
        // 步骤落库应携带智能体编码，供技能调用统计使用
        org.mockito.ArgumentCaptor<AgentToolStep> stepCaptor = org.mockito.ArgumentCaptor.forClass(AgentToolStep.class);
        verify(stepMapper).insert(stepCaptor.capture());
        assertEquals("kb-helper", stepCaptor.getValue().getAgentCode());
        assertEquals(List.of(
                "status:正在分析问题，决定是否调用工具…",
                "answer_reset",
                "status:正在调用工具…",
                "step_start:echo",
                "step_result:SUCCESS:回显：你好",
                "status:正在结合工具结果思考…",
                "delta:工具结果是",
                "delta:：回显：你好",
                "status:null",
                "answer",
                "done"
        ), listener.events);
        assertEquals("工具结果是：回显：你好", listener.answer);
        assertEquals("工具结果是：回显：你好", listener.streamed.toString());
        // system + user + ai(工具调用) + 工具结果 + ai(最终回答)
        assertEquals(5, messages.size());
        verify(stepMapper, times(1)).insert(any(AgentToolStep.class));
    }

    @Test
    void shouldExposeErrorMessageWhenToolFails() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("boom")
                .arguments("{}")
                .build();
        FakeStreamingModel model = new FakeStreamingModel(List.of(
                new ScriptedStep(List.of(), Response.from(AiMessage.from(request))),
                new ScriptedStep(List.of("工具失败了"), Response.from(AiMessage.from("工具失败了")))
        ));

        AgentToolStepMapper stepMapper = mock(AgentToolStepMapper.class);
        // service 持有的 registry 决定工具能否执行，必须与请求的 boom 工具一致
        AgentToolService service = newService(stepMapper,
                new SkillRegistry(List.of(new BoomTool()), new ObjectMapper(), skillMapper, agentSkillMapper, Runnable::run, 120000));
        CollectingListener listener = new CollectingListener();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.systemMessage("system"),
                UserMessage.userMessage("试试会失败的工具")
        ));

        service.runStreamLoop(model, messages, "run-err", listener, new AtomicInteger(), registryToolsWith(new BoomTool()), 42L, "kb-helper");

        // 失败原因应随 step_result 事件推给前端（执行过程时间线展示用）
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("step_result:FAILED:") && e.contains("数据库连接超时")),
                "step_result 事件应携带失败原因，实际事件：" + listener.events);
        // 落库同样携带失败原因与状态
        org.mockito.ArgumentCaptor<AgentToolStep> captor = org.mockito.ArgumentCaptor.forClass(AgentToolStep.class);
        verify(stepMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("数据库连接超时", captor.getValue().getErrorMessage());
    }

    @Test
    void shouldStopAtMaxRoundsWhenModelAlwaysCallsTools() {
        FakeStreamingModel model = new FakeStreamingModel(List.of(
                new ScriptedStep(List.of(), Response.from(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-loop")
                        .name("echo")
                        .arguments("{\"text\":\"循环\"}")
                        .build())))
        ));

        AgentToolService service = newService(mock(AgentToolStepMapper.class));
        CollectingListener listener = new CollectingListener();

        service.runStreamLoop(model, new ArrayList<>(List.of(UserMessage.userMessage("hi"))), "run-2", listener, new AtomicInteger(), registryTools(), null, null);

        assertTrue(listener.answer.contains("已连续调用工具"), listener.answer);
        assertEquals(AgentToolService.MAX_ROUNDS, listener.events.stream().filter(e -> e.startsWith("step_start")).count());
        assertTrue(listener.done);
    }

    @Test
    void shouldStreamFinalAnswerDeltas() {
        FakeStreamingModel model = new FakeStreamingModel(List.of(
                new ScriptedStep(List.of("今天", "南京", "有雨"), Response.from(AiMessage.from("今天南京有雨")))
        ));

        AgentToolService service = newService(mock(AgentToolStepMapper.class));
        CollectingListener listener = new CollectingListener();

        service.runStreamLoop(model, new ArrayList<>(List.of(UserMessage.userMessage("天气"))), "run-3", listener, new AtomicInteger(), registryTools(), null, null);

        assertEquals(List.of(
                "status:正在分析问题，决定是否调用工具…",
                "delta:今天", "delta:南京", "delta:有雨",
                "status:null",
                "answer", "done"), listener.events);
        assertEquals("今天南京有雨", listener.streamed.toString());
    }

    // —— 步骤编排执行 ——

    /** 构造一个步骤定义 */
    private AgentStep step(int stepNo, String type, String toolName, String argsTemplate, String instruction) {
        AgentStep step = new AgentStep();
        step.setStepNo(stepNo);
        step.setStepType(type);
        step.setToolName(toolName);
        step.setArgsTemplate(argsTemplate);
        step.setInstruction(instruction);
        step.setEnabled(1);
        return step;
    }

    @Test
    void shouldRunConfiguredStepsInOrder() {
        // 第1步调用 echo 并把用户问题塞进参数；第2步让模型加工；第3步只作为最终回答的收尾要求
        List<AgentStep> steps = List.of(
                step(1, AgentStep.TYPE_TOOL, "echo", "{\"text\":\"${input}\"}", null),
                step(2, AgentStep.TYPE_LLM, null, null, "把结果整理成一句话"),
                step(3, AgentStep.TYPE_ANSWER, null, null, "用表格输出")
        );
        FakeStreamingModel model = new FakeStreamingModel(List.of(
                new ScriptedStep(List.of(), Response.from(AiMessage.from("整理后的结论"))),
                new ScriptedStep(List.of("最终", "回答"), Response.from(AiMessage.from("最终回答")))
        ));

        AgentToolStepMapper stepMapper = mock(AgentToolStepMapper.class);
        AgentToolService service = newService(stepMapper);
        CollectingListener listener = new CollectingListener();
        AgentToolChatRequest request = new AgentToolChatRequest();
        request.setAgentCode("custom-1-abc");
        request.setMessage("北京今天热吗");

        String answer = service.runStepPlan(model, request, steps, "system", "run-step", listener,
                new AtomicInteger(), 42L, "custom-1-abc");

        assertEquals("最终回答", answer);
        // 占位符 ${input} 应替换成用户问题后传给工具
        assertTrue(listener.events.contains("step_start:echo"), listener.events.toString());
        assertTrue(listener.events.contains("step_result:SUCCESS:回显：北京今天热吗"), listener.events.toString());
        // LLM 步骤不暴露工具
        assertTrue(listener.events.contains("step_start:model"), listener.events.toString());
        assertTrue(listener.events.contains("step_result:SUCCESS:整理后的结论"), listener.events.toString());
        assertTrue(listener.done);
        // 两个真正执行的步骤各自落库，收尾要求不落库
        verify(stepMapper, times(2)).insert(any(AgentToolStep.class));
    }

    @Test
    void shouldStopRemainingStepsWhenOneStepFails() {
        List<AgentStep> steps = List.of(
                step(1, AgentStep.TYPE_TOOL, "boom", "{}", null),
                step(2, AgentStep.TYPE_TOOL, "echo", "{\"text\":\"${step1}\"}", null)
        );
        FakeStreamingModel model = new FakeStreamingModel(List.of(
                new ScriptedStep(List.of(), Response.from(AiMessage.from("不应被调用")))
        ));

        AgentToolStepMapper stepMapper = mock(AgentToolStepMapper.class);
        AgentToolService service = newService(stepMapper,
                new SkillRegistry(List.of(new BoomTool(), new EchoTool()), new ObjectMapper(),
                        skillMapper, agentSkillMapper, Runnable::run, 120000));
        CollectingListener listener = new CollectingListener();
        AgentToolChatRequest request = new AgentToolChatRequest();
        request.setAgentCode("custom-1-boom");
        request.setMessage("触发失败");

        String answer = service.runStepPlan(model, request, steps, "system", "run-fail", listener,
                new AtomicInteger(), 42L, "custom-1-boom");

        assertTrue(answer.contains("执行失败") && answer.contains("数据库连接超时"), answer);
        assertTrue(listener.done);
        // 第一步失败后，第二步不应继续执行
        assertTrue(listener.events.stream().noneMatch(e -> e.equals("step_start:echo")), listener.events.toString());
        verify(stepMapper, times(1)).insert(any(AgentToolStep.class));
    }

    @Test
    void shouldRenderStepPlaceholders() {
        AgentToolService service = newService(mock(AgentToolStepMapper.class));

        assertEquals("{}", service.buildToolArguments(null, "问题", List.of()));
        assertEquals("{\"city\":\"北京\"}", service.buildToolArguments("{\"city\":\"${input}\"}", "北京", List.of()));
        assertEquals("{\"a\":\"第一结果\",\"b\":\"北京\"}",
                service.buildToolArguments("{\"a\":\"${step1}\",\"b\":\"${input}\"}", "北京", List.of("第一结果")));
        // 引号与换行要转义，避免生成非法 JSON
        assertEquals("{\"a\":\"他说\\\"好\\\"\"}",
                service.buildToolArguments("{\"a\":\"${input}\"}", "他说\"好\"", List.of()));
        // 没有对应结果的占位符应被清掉
        assertEquals("{\"a\":\"\"}", service.buildToolArguments("{\"a\":\"${step9}\"}", "问题", List.of()));
    }
}
