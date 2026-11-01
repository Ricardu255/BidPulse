package com.bidpulse.auth;

import com.bidpulse.audit.AuditService;
import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.console.api.ConsoleAuthController;
import testonly.SecurityProbeController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全策略切片测试：不连数据库，只验证 401/403/角色边界与登录契约。
 */
@WebMvcTest(controllers = {SecurityProbeController.class, ConsoleAuthController.class})
@Import({SecurityConfig.class, SecurityConfigTest.TestBeans.class, SecurityProbeController.class})
@TestPropertySource(properties = {
        "bidpulse.console.enabled=true",
        "bidpulse.auth.secret=test-secret-please-change-me-32-bytes",
        "bidpulse.auth.users.admin.password=admin-pass",
        "bidpulse.auth.users.admin.role=ADMIN",
        "bidpulse.auth.users.operator.password=operator-pass",
        "bidpulse.auth.users.operator.role=OPERATOR",
        "bidpulse.auth.users.viewer.password=viewer-pass",
        "bidpulse.auth.users.viewer.role=VIEWER"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private AuditService auditService;

    @Test
    @DisplayName("未带 token 访问控制台返回 401")
    void unauthenticatedConsoleReturns401() throws Exception {
        mockMvc.perform(get("/console-api/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(auditService).record(eq("anonymous"), eq("ANONYMOUS"), eq("GET"),
                eq("/console-api/v1/probe"), eq(401), anyString(), isNull());
    }

    @Test
    @DisplayName("登录接口公开，错误密码返回 401 并写审计")
    void loginIsPublicAndFailureIsAudited() throws Exception {
        mockMvc.perform(post("/console-api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viewer\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        verify(auditService).record(eq("anonymous"), eq("ANONYMOUS"), eq("LOGIN"),
                anyString(), eq(401), anyString(), anyString());
    }

    @Test
    @DisplayName("VIEWER 可读控制台，但不能写")
    void viewerCanReadButNotWrite() throws Exception {
        String viewer = token("viewer", "viewer-pass");

        mockMvc.perform(get("/console-api/v1/probe").header("Authorization", viewer))
                .andExpect(status().isOk());
        mockMvc.perform(put("/console-api/v1/probe").header("Authorization", viewer))
                .andExpect(status().isForbidden());

        verify(auditService).record(eq("viewer"), eq("VIEWER"), eq("PUT"),
                eq("/console-api/v1/probe"), eq(403), anyString(), isNull());
    }

    @Test
    @DisplayName("OPERATOR 可执行控制台写操作")
    void operatorCanWriteConsole() throws Exception {
        String operator = token("operator", "operator-pass");

        mockMvc.perform(put("/console-api/v1/probe").header("Authorization", operator))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN 才能写 /admin/** 和读审计，VIEWER 均 403")
    void adminEndpointsRequireAdmin() throws Exception {
        String viewer = token("viewer", "viewer-pass");
        String admin = token("admin", "admin-pass");

        mockMvc.perform(put("/admin/probe").header("Authorization", viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/probe").header("Authorization", admin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/console-api/v1/audit").header("Authorization", viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console-api/v1/audit").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Actuator 指标仅 ADMIN，/bid 保持公开")
    void actuatorRequiresAdminAndBidIsPublic() throws Exception {
        String viewer = token("viewer", "viewer-pass");
        String admin = token("admin", "admin-pass");

        mockMvc.perform(get("/actuator/probe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/probe").header("Authorization", viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/probe").header("Authorization", admin))
                .andExpect(status().isOk());

        mockMvc.perform(post("/bid")).andExpect(status().isOk());
    }

    private String token(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/console-api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).path("data").path("token").asText();
        return "Bearer " + token;
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        BidPulseProperties bidPulseProperties() {
            BidPulseProperties props = new BidPulseProperties();
            props.getAuth().setSecret("test-secret-please-change-me-32-bytes");
            Map<String, BidPulseProperties.AuthUser> users = new LinkedHashMap<>();
            users.put("admin", user("admin-pass", "ADMIN"));
            users.put("operator", user("operator-pass", "OPERATOR"));
            users.put("viewer", user("viewer-pass", "VIEWER"));
            props.getAuth().setUsers(users);
            return props;
        }

        @Bean
        AuthService authService(BidPulseProperties props, ObjectMapper mapper) {
            return new AuthService(props, mapper);
        }

        @Bean
        TokenAuthFilter tokenAuthFilter(AuthService authService) {
            return new TokenAuthFilter(authService);
        }

        private static BidPulseProperties.AuthUser user(String password, String role) {
            BidPulseProperties.AuthUser user = new BidPulseProperties.AuthUser();
            user.setPassword(password);
            user.setRole(role);
            return user;
        }
    }

}
