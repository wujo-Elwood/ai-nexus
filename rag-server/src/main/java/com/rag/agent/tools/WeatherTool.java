package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 天气查询工具：按经纬度查询当前天气
 * 坐标来自 search_city_location 工具的结果，两者配合形成两步执行链路
 */
@Component
public class WeatherTool extends OpenMeteoSupport {

    public WeatherTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "根据经纬度查询当前天气，返回温度、天气现象、湿度、风速和今日气温范围。坐标请来自 search_city_location 的结果。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("latitude", "number", "纬度，来自 search_city_location 的结果", true),
                new ToolParam("longitude", "number", "经度，来自 search_city_location 的结果", true),
                new ToolParam("city", "string", "城市名称，仅用于结果展示", false));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String lat = arguments.path("latitude").asText("").trim();
        String lon = arguments.path("longitude").asText("").trim();
        if (lat.isEmpty() || lon.isEmpty() || "0".equals(lat) && "0".equals(lon)) {
            return "参数错误：需要传入 latitude 和 longitude，请先调用 search_city_location 获取";
        }
        String city = arguments.path("city").asText("").trim();

        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
        JsonNode forecast = objectMapper.readTree(fetchJson(url));
        JsonNode current = forecast.path("current");
        if (current.isMissingNode()) {
            return "天气接口未返回当前数据，请稍后重试";
        }
        StringBuilder text = new StringBuilder();
        text.append(city.isEmpty() ? "该地区" : city).append(" 当前天气：")
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
}
