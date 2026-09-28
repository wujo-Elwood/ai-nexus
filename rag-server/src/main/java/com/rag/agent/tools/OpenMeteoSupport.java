package com.rag.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Open-Meteo 工具公共支撑：HTTP 客户端、接口拉取和天气代码翻译
 * 拆分为 search_city_location（城市定位）和 get_weather（按坐标查天气）两个工具，
 * 模型需要先定位再查天气，执行过程天然形成两步
 */
public abstract class OpenMeteoSupport implements AgentTool {

    protected final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    protected OpenMeteoSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 请求 JSON 接口，独立成方法便于测试时打桩 */
    protected String fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("接口返回 " + response.statusCode());
        }
        return response.body();
    }

    /** WMO 天气代码转中文描述 */
    protected String weatherText(int code) {
        if (code == 0) return "晴";
        if (code == 1 || code == 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code == 85 || code == 86) return "阵雪";
        if (code >= 95) return "雷阵雨";
        return "未知天气(" + code + ")";
    }
}
