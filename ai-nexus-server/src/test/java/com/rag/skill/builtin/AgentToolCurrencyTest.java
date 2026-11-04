package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汇率与计算器工具单元测试：打桩 HTTP 层，验证解析与表达式求值
 */
class AgentToolCurrencyTest {

    private static final String RATE_JSON = "{\"amount\":1.0,\"base\":\"CNY\",\"date\":\"2026-09-28\","
            + "\"rates\":{\"USD\":0.14902}}";

    private static class StubExchangeRateTool extends ExchangeRateTool {
        StubExchangeRateTool() {
            super(new ObjectMapper());
        }

        @Override
        protected String fetchJson(String url) {
            if (url.contains("symbols=XXX")) {
                return "{\"message\":\"not found\"}";
            }
            return RATE_JSON;
        }
    }

    @Test
    void shouldReturnRateForValidPair() throws Exception {
        ExchangeRateTool tool = new StubExchangeRateTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"from\":\"cny\",\"to\":\"USD\"}"));
        assertTrue(result.contains("1 CNY = 0.14902 USD"), result);
        assertTrue(result.contains("2026-09-28"), result);
        assertTrue(result.contains("calculate"), result);
    }

    @Test
    void shouldReturnNotFoundForUnknownCurrency() throws Exception {
        ExchangeRateTool tool = new StubExchangeRateTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"from\":\"CNY\",\"to\":\"XXX\"}"));
        assertTrue(result.contains("未查到"), result);
    }

    @Test
    void shouldRejectBadCurrencyCode() throws Exception {
        ExchangeRateTool tool = new StubExchangeRateTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"from\":\"人民币\",\"to\":\"USD\"}"));
        assertTrue(result.contains("参数错误"), result);
    }

    @Test
    void shouldEvaluateArithmeticExpression() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"expression\":\"(1000 * 0.14902) + 25\"}"));
        assertTrue(result.contains("= 174.02"), result);
    }

    @Test
    void shouldRejectDivisionByZero() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"expression\":\"10 / 0\"}"));
        assertTrue(result.contains("除数不能为零"), result);
    }

    @Test
    void shouldRejectUnsafeExpression() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"expression\":\"Runtime.getRuntime()\"}"));
        assertTrue(result.contains("无法计算") || result.contains("缺少数字"), result);
    }
}
