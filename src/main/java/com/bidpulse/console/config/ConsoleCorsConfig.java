package com.bidpulse.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 前后端分离部署时的 CORS 配置。
 *
 * <p>开发态用 Vite 代理时同源，不会触发 CORS；把前端单独部署到另一个域名/端口时，
 * 通过 {@code bidpulse.console.allowed-origins} 显式放行，避免使用 {@code *}。
 */
@Configuration
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
public class ConsoleCorsConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public ConsoleCorsConfig(
            @Value("${bidpulse.console.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/console-api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
