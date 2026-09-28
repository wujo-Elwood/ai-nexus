package com.rag.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.agent.entity.AgentToolStep;
import com.rag.agent.mapper.AgentToolStepMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 一条脚本：先输出的增量文本 + 最终响应 */
    private record ScriptedStep(List<String> deltas, Response<AiMessage> response) {
    }

    /** 按脚本顺序回放的假流式模型 */
    private static class FakeStreamingModel implements StreamingChatLanguageModel {
        private final List<ScriptedStep> script;
        private int calls = 0;

        FakeStreamingModel(List<ScriptedStep> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications,
                             StreamingResponseHandler<AiMessage> handler) {
            ScriptedStep step = script.get(Math.min(calls++, script.size() - 1));
            for (String delta : step.deltas()) {
                handler.onNext(delta);
            }
            handler.onComplete(step.response());
        }
    }

    /** 只做记录的空工具 */
    private static class EchoTool implements AgentTool {
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
            return "回显：" + arguments.path("text").asText("");
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
        public void onStepResult(int stepNo, String result, long costMs, String status) {
            events.add("step_result:" + status + ":" + result);
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

    private AgentToolService newService(AgentToolStepMapper stepMapper) {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()), new ObjectMapper());
        // 同线程执行器：测试中工具执行和下一轮递归按顺序同步跑完
        return new AgentToolService(registry, null, stepMapper, new ObjectMapper(), Runnable::run);
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

        service.runStreamLoop(model, messages, "run-1", listener, 0, new AtomicInteger());

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

        service.runStreamLoop(model, new ArrayList<>(List.of(UserMessage.userMessage("hi"))), "run-2", listener, 0, new AtomicInteger());

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

        service.runStreamLoop(model, new ArrayList<>(List.of(UserMessage.userMessage("天气"))), "run-3", listener, 0, new AtomicInteger());

        assertEquals(List.of(
                "status:正在分析问题，决定是否调用工具…",
                "delta:今天", "delta:南京", "delta:有雨",
                "status:null",
                "answer", "done"), listener.events);
        assertEquals("今天南京有雨", listener.streamed.toString());
    }
}
