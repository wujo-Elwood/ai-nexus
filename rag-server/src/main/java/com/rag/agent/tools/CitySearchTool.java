package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 城市定位工具：把城市名解析为经纬度坐标
 * 和 get_weather 拆分开，让模型形成"先定位、再查天气"的多步执行链路
 */
@Component
public class CitySearchTool extends OpenMeteoSupport {

    public CitySearchTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String name() {
        return "search_city_location";
    }

    @Override
    public String description() {
        return "把城市名解析为经纬度坐标。查询天气前必须先调用它获取城市坐标。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(new ToolParam("city", "string", "城市名称，例如：南京、北京、New York", true));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String city = arguments.path("city").asText("").trim();
        if (city.isEmpty()) {
            return "参数错误：city 不能为空";
        }
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&count=1&language=zh&format=json";
        JsonNode first = objectMapper.readTree(fetchJson(geoUrl)).path("results").path(0);
        if (first.isMissingNode()) {
            return "未找到城市「" + city + "」，请确认城市名后重试";
        }
        String name = first.path("name").asText(city);
        String country = first.path("country").asText("");
        String admin1 = first.path("admin1").asText("");
        String location = (admin1.isEmpty() ? "" : admin1 + " · ") + country;
        return "城市：" + name + (location.isBlank() ? "" : "（" + location + "）")
                + "\n纬度：" + first.path("latitude").asText()
                + "，经度：" + first.path("longitude").asText()
                + "\n下一步请调用 get_weather 并传入以上经纬度查询天气。";
    }
}
