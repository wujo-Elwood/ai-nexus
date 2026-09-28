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
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 通用工具智能体执行循环测试：用脚本化的假模型验证步骤回调、结果回填和落库
 */
class AgentToolServiceTest {

    /** 按脚本顺序返回响应的假模型 */
    private static class FakeChatModel implements ChatLanguageModel {
        private final List<Response<AiMessage>> script;
        private int calls = 0;

        FakeChatModel(List<Response<AiMessage>> script) {
            this.script = script;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
            return script.get(Math.min(calls++, script.size() - 1));
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
        String answer = "";

        @Override
        public void onOpen(String runId, Object tools) {
            events.add("open");
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
        public void onAnswer(String content) {
            events.add("answer");
            answer = content;
        }

        @Override
        public void onDone(String runId) {
            events.add("done");
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }
    }

    private AgentToolService newService(AgentToolStepMapper stepMapper) {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()), new ObjectMapper());
        return new AgentToolService(registry, null, stepMapper, new ObjectMapper(), null);
    }

    @Test
    void shouldExecuteToolAndAnswer() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("echo")
                .arguments("{\"text\":\"你好\"}")
                .build();
        FakeChatModel model = new FakeChatModel(List.of(
                Response.from(AiMessage.from(request)),
                Response.from(AiMessage.from("工具结果是：回显：你好"))
        ));

        AgentToolStepMapper stepMapper = mock(AgentToolStepMapper.class);
        AgentToolService service = newService(stepMapper);
        CollectingListener listener = new CollectingListener();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.systemMessage("system"),
                UserMessage.userMessage("调用工具试试")
        ));

        String answer = service.runLoop(model, messages, "run-1", listener);

        assertEquals("工具结果是：回显：你好", answer);
        // open 事件由 doChat 的 SSE 包装层发出，runLoop 只负责步骤与回答
        assertEquals(List.of("step_start:echo", "step_result:SUCCESS:回显：你好", "answer"), listener.events);
        // system + user + ai(工具调用) + 工具结果 + ai(最终回答)
        assertEquals(5, messages.size());
        verify(stepMapper, times(1)).insert(any(AgentToolStep.class));
    }

    @Test
    void shouldStopAtMaxRoundsWhenModelAlwaysCallsTools() {
        FakeChatModel model = new FakeChatModel(List.of(
                Response.from(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-loop")
                        .name("echo")
                        .arguments("{\"text\":\"循环\"}")
                        .build()))
        ));

        AgentToolService service = newService(mock(AgentToolStepMapper.class));
        CollectingListener listener = new CollectingListener();

        String answer = service.runLoop(model, new ArrayList<>(List.of(UserMessage.userMessage("hi"))), "run-2", listener);

        assertTrue(answer.contains("已连续调用工具"), answer);
        assertEquals(AgentToolService.MAX_ROUNDS, listener.events.stream().filter(e -> e.startsWith("step_start")).count());
    }
}
