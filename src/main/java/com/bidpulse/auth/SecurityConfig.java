package com.bidpulse.auth;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 控制台/管理端安全策略。
 *
 * <p>认证是无状态 Bearer token（见 {@link AuthService}），不使用 session，因此关闭 CSRF：
 * 浏览器不会自动携带 Authorization 头，跨站表单也无法伪造写请求。
 *
 * <p>权限矩阵：
 * <ul>
 *   <li>公开：{@code POST /bid}、health/info、登录接口、CORS 预检；</li>
 *   <li>VIEWER：所有控制台/管理端 GET；</li>
 *   <li>OPERATOR：控制台写操作（广告、预算、实验、Kafka、DLT、对账）；</li>
 *   <li>ADMIN：{@code /admin/**} 写操作、Actuator 指标、审计日志。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final TokenAuthFilter tokenAuthFilter;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/bid").permitAll()
                        .requestMatchers(HttpMethod.POST, "/console-api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()

                        .requestMatchers(HttpMethod.GET, "/console-api/v1/audit", "/console-api/v1/audit/**")
                        .hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.GET, "/console-api/**", "/admin/**")
                        .hasAnyRole(Role.VIEWER.name(), Role.OPERATOR.name(), Role.ADMIN.name())
                        .requestMatchers("/console-api/**", "/admin/**")
                        .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name())
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            auditDenied(request, 401);
                            writeJson(response, 401, "未登录或登录已过期，请重新登录");
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            auditDenied(request, 403);
                            writeJson(response, 403, "当前角色无权执行该操作");
                        }))
                .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 阻止 Spring Boot 生成默认 user/随机密码；本项目的账号由 AuthService 管理。 */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    /** 被安全层拒绝的请求也写审计：403/401 同样需要回答“谁在什么时候尝试了什么”。 */
    private void auditDenied(HttpServletRequest request, int status) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = "anonymous";
        String role = "ANONYMOUS";
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            actor = principal.username();
            role = principal.role().name();
        }
        auditService.record(actor, role, request.getMethod(), request.getRequestURI(), status,
                AuditService.clientIp(request), request.getQueryString());
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
