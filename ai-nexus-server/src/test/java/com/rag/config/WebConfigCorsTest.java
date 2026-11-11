package com.rag.config;

import com.rag.controller.AuthController;
import com.rag.rbac.service.RbacService;
import com.rag.service.UserService;
import com.rag.utils.JwtUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 跨域配置测试
 * 验证本机域名、回环地址和局域网地址都能访问前端开发服务
 */
@WebMvcTest(
        controllers = AuthController.class,
        properties = "security.cors.allowed-origin-patterns=http://*:5173"
)
@ContextConfiguration(classes = AuthController.class)
@Import(WebConfig.class)
class WebConfigCorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private RbacService rbacService;

    /**
     * 测试不同主机来源的登录预检请求均被允许
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://192.168.0.96:5173"
    })
    void loginPreflightShouldAllowFrontendDevelopmentOrigins(String origin) throws Exception {
        // 发起浏览器登录前的跨域预检请求并检查返回来源
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }
}
