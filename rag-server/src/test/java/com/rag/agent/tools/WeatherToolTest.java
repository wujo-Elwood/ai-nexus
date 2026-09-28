package com.rag.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天气工具单元测试：打桩 HTTP 层，验证地理编码和天气解析逻辑
 */
class WeatherToolTest {

    /** 返回打桩 JSON 的天气工具 */
    private static class StubWeatherTool extends WeatherTool {
        StubWeatherTool() {
            super(new ObjectMapper());
        }

        @Override
        protected String fetchJson(String url) throws Exception {
            if (url.contains("geocoding-api")) {
                if (url.contains("unknown-city")) {
                    return "{\"results\":[]}";
                }
                return "{\"results\":[{\"name\":\"北京\",\"latitude\":39.9,\"longitude\":116.4,\"country\":\"中国\"}]}";
            }
            return "{\"current\":{\"temperature_2m\":26.3,\"relative_humidity_2m\":40,\"weather_code\":2,"
                    + "\"wind_speed_10m\":12.2},\"daily\":{\"temperature_2m_max\":[31.0],\"temperature_2m_min\":[22.5]}}";
        }
    }

    @Test
    void shouldReturnWeatherTextForValidCity() throws Exception {
        WeatherTool tool = new StubWeatherTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"city\":\"北京\"}"));
        assertTrue(result.contains("北京"), result);
        assertTrue(result.contains("26.3"), result);
        assertTrue(result.contains("多云"), result);
        assertTrue(result.contains("22.5"), result);
        assertTrue(result.contains("31.0"), result);
    }

    @Test
    void shouldReturnNotFoundForUnknownCity() throws Exception {
        WeatherTool tool = new StubWeatherTool();
        String result = tool.execute(new ObjectMapper().readTree("{\"city\":\"unknown-city\"}"));
        assertTrue(result.contains("未找到城市"), result);
    }

    @Test
    void shouldRejectEmptyCity() throws Exception {
        WeatherTool tool = new StubWeatherTool();
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        assertFalse(result.contains("°C"), result);
        assertTrue(result.contains("参数错误"), result);
    }
}
