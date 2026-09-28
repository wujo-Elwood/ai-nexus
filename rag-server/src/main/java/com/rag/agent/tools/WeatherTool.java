package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 天气查询工具：通用智能体的第一个工具
 * 数据来自 Open-Meteo 免费接口，无需 API Key；先地理编码拿到城市坐标，再查询当前天气
 */
@Component
public class WeatherTool implements AgentTool {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WeatherTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "query_weather";
    }

    @Override
    public String description() {
        return "查询指定城市当前的天气情况，返回温度、天气现象、湿度、风速和今日气温范围。支持全球中英文城市名。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(new ToolParam("city", "string", "城市名称，例如：北京、上海、New York", true));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String city = arguments.path("city").asText("").trim();
        if (city.isEmpty()) {
            return "参数错误：city 不能为空";
        }
        // 第1步：地理编码，把城市名转换为经纬度
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&count=1&language=zh&format=json";
        JsonNode first = objectMapper.readTree(fetchJson(geoUrl)).path("results").path(0);
        if (first.isMissingNode()) {
            return "未找到城市「" + city + "」，请确认城市名后重试";
        }
        double lat = first.path("latitude").asDouble();
        double lon = first.path("longitude").asDouble();
        String resolvedName = first.path("name").asText(city);

        // 第2步：按坐标查询当前天气
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
        JsonNode forecast = objectMapper.readTree(fetchJson(url));
        JsonNode current = forecast.path("current");
        if (current.isMissingNode()) {
            return "天气接口未返回当前数据，请稍后重试";
        }
        StringBuilder text = new StringBuilder();
        text.append(resolvedName).append(" 当前天气：")
                .append(current.path("temperature_2m").asDouble()).append("°C，")
                .append(weatherText(current.path("weather_code").asInt()))
                .append("，湿度 ").append(current.path("relative_humidity_2m").asInt()).append("%")
                .append("，风速 ").append(current.path("wind_speed_10m").asDouble()).append(" km/h");
        JsonNode daily = forecast.path("daily");
        String tMax = daily.path("temperature_2m_max").path(0).asText("");
        String tMin = daily.path("temperature_2m_min").path(0).asText("");
        if (!tMax.isEmpty() && !tMin.isEmpty()) {
            text.append("；今日 ").append(tMin).append("°C ~ ").append(tMax).append("°C");
        }
        return text.toString();
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
            throw new IllegalStateException("天气接口返回 " + response.statusCode());
        }
        return response.body();
    }

    /** WMO 天气代码转中文描述 */
    private String weatherText(int code) {
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
