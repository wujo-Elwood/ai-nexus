package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 汇率查询工具：基于 Frankfurter 免费接口（欧洲央行数据，无需 API Key）
 * 和 calculate 工具配合完成金额换算，形成多步执行链路
 */
@Component
public class ExchangeRateTool implements AgentTool {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ExchangeRateTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_exchange_rate";
    }

    @Override
    public String description() {
        return "查询两种货币之间的实时汇率，返回 1 单位源币种兑换目标币种的金额。币种使用 3 位 ISO 代码，如 CNY、USD、EUR、JPY、GBP、KRW。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("from", "string", "源币种 ISO 代码，例如 CNY", true),
                new ToolParam("to", "string", "目标币种 ISO 代码，例如 USD", true));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String from = arguments.path("from").asText("").trim().toUpperCase();
        String to = arguments.path("to").asText("").trim().toUpperCase();
        if (from.isEmpty() || to.isEmpty()) {
            return "参数错误：from 和 to 不能为空";
        }
        if (!from.matches("[A-Z]{3}") || !to.matches("[A-Z]{3}")) {
            return "参数错误：币种需使用 3 位英文 ISO 代码（如 CNY、USD、EUR、JPY、GBP、KRW），不要传中文币种名";
        }
        String url = "https://api.frankfurter.dev/v1/latest?base=" + from + "&symbols=" + to;
        JsonNode response = objectMapper.readTree(fetchJson(url));
        if (response.hasNonNull("message")) {
            return "未查到 " + from + " 兑 " + to + " 的汇率，请确认币种代码是否受支持";
        }
        JsonNode rate = response.path("rates").path(to);
        if (rate.isMissingNode()) {
            return "未查到 " + from + " 兑 " + to + " 的汇率，请确认币种代码是否受支持";
        }
        return "1 " + from + " = " + rate.asText() + " " + to
                + "（汇率日期：" + response.path("date").asText("") + "）"
                + "。换算金额请把金额乘以该汇率，可使用 calculate 工具计算。";
    }

    /** 请求 JSON 接口，独立成方法便于测试时打桩；404 视为币种不受支持，由上层转为友好提示 */
    protected String fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return "{\"message\":\"not found\"}";
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("汇率接口返回 " + response.statusCode());
        }
        return response.body();
    }
}
