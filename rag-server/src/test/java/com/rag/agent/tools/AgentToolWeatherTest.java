package com.rag.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 城市定位与天气查询工具单元测试：打桩 HTTP 层，验证参数解析和结果拼装
 */
class AgentToolWeatherTest {

    private static final String GEO_JSON = "{\"results\":[{\"name\":\"南京\",\"latitude\":32.06,\"longitude\":118.8,"
            + "\"country\":\"中国\",\"admin1\":\"江苏省\"}]}";
    private static final String FORECAST_JSON = "{\"current\":{\"temperature_2m\":21.8,\"relative_humidity_2m\":88,"
            + "\"weather_code\":51,\"wind_speed_10m\":19.0},\"daily\":{\"temperature_2m_max\":[23.4],"
            + "\"temperature_2m_min\":[19.8]}}";

    private static class StubCitySearchTool extends CitySearchTool {
        StubCitySearchTool() {
            super(new ObjectMapper());
        }

        @Override
        protected String fetchJson(String url) {
            return url.contains("unknown-city") ? "{\"results\":[]}" : GEO_JSON;
        }
    }

    private static class StubWeatherTool extends WeatherTool {
        StubWeatherTool() {
            super(new ObjectMapper());
        }

        @Override
        protected String fetchJson(String url) {
            return FORECAST_JSON;
        }
    }

    @Test
    void shouldResolveCityToCoordinates() throws Exception {
        CitySearchTool tool = new StubCitySearchTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"city\":\"南京\"}"));
        assertTrue(result.contains("南京"), result);
        assertTrue(result.contains("32.06"), result);
        assertTrue(result.contains("118.8"), result);
        assertTrue(result.contains("get_weather"), result);
    }

    @Test
    void shouldReturnNotFoundForUnknownCity() throws Exception {
        CitySearchTool tool = new StubCitySearchTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"city\":\"unknown-city\"}"));
        assertTrue(result.contains("未找到城市"), result);
    }

    @Test
    void shouldReturnWeatherForCoordinates() throws Exception {
        WeatherTool tool = new StubWeatherTool();
        String result = tool.execute(new ObjectMapper().readTree(
                "{\"city\":\"南京\",\"latitude\":32.06,\"longitude\":118.8}"));
        assertTrue(result.contains("南京"), result);
        assertTrue(result.contains("21.8"), result);
        assertTrue(result.contains("毛毛雨"), result);
        assertTrue(result.contains("19.8"), result);
        assertTrue(result.contains("23.4"), result);
    }

    @Test
    void shouldRejectMissingCoordinates() throws Exception {
        WeatherTool tool = new StubWeatherTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"city\":\"南京\"}"));
        assertTrue(result.contains("参数错误"), result);
        assertTrue(result.contains("search_city_location"), result);
    }
}
