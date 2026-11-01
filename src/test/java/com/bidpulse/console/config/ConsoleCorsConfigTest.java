package com.bidpulse.console.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 配置单测：不启动 MVC，只验证注册结果。
 */
class ConsoleCorsConfigTest {

    @Test
    @DisplayName("放行 localhost/127.0.0.1 并允许 PUT 预检")
    void registersOriginsAndPutMethod() {
        CapturingCorsRegistry registry = new CapturingCorsRegistry();
        new ConsoleCorsConfig("http://localhost:5173, http://127.0.0.1:5173")
                .addCorsMappings(registry);

        CorsConfiguration config = registry.configs().get("/console-api/**");
        assertNotNull(config);
        assertEquals(2, config.getAllowedOrigins().size());
        assertTrue(config.getAllowedOrigins().contains("http://localhost:5173"));
        assertTrue(config.getAllowedOrigins().contains("http://127.0.0.1:5173"));
        assertTrue(config.getAllowedMethods().contains("PUT"));
        assertTrue(config.getAllowedMethods().contains("DELETE"));
    }

    @Test
    @DisplayName("空项/空白来源被忽略")
    void ignoresBlankOrigins() {
        CapturingCorsRegistry registry = new CapturingCorsRegistry();
        new ConsoleCorsConfig("http://localhost:5173, , http://127.0.0.1:5173")
                .addCorsMappings(registry);

        assertEquals(2, registry.configs().get("/console-api/**").getAllowedOrigins().size());
    }

    private static final class CapturingCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }
}
