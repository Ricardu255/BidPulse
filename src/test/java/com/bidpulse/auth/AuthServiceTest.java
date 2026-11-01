package com.bidpulse.auth;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("登录成功返回可验签的 token 与角色")
    void loginReturnsVerifiableToken() {
        AuthService service = service();
        AuthService.LoginResult result = service.login("admin", "admin-pass");

        assertEquals("admin", result.username());
        assertEquals("ADMIN", result.role());
        AuthPrincipal principal = service.verify(result.token()).orElseThrow();
        assertEquals("admin", principal.username());
        assertEquals(Role.ADMIN, principal.role());
    }

    @Test
    @DisplayName("密码错误与用户不存在都返回 401")
    void badCredentialsReturn401() {
        AuthService service = service();

        assertEquals(401, assertThrows(BizException.class,
                () -> service.login("admin", "wrong")).getStatus());
        assertEquals(401, assertThrows(BizException.class,
                () -> service.login("nobody", "admin-pass")).getStatus());
    }

    @Test
    @DisplayName("篡改 token 无法通过验签")
    void tamperedTokenRejected() {
        AuthService service = service();
        String token = service.login("viewer", "viewer-pass").token();

        assertEquals(service.verify(token).isPresent(), true);
        String tampered = (token.charAt(0) == 'a' ? "b" : "a") + token.substring(1);
        assertTrue(service.verify(tampered).isEmpty());
        assertTrue(service.verify(token + "x").isEmpty());
        assertTrue(service.verify("").isEmpty());
        assertTrue(service.verify(null).isEmpty());
    }

    @Test
    @DisplayName("非法角色降级为 VIEWER")
    void invalidRoleFallsBackToViewer() {
        BidPulseProperties props = props();
        props.getAuth().getUsers().get("viewer").setRole("ROOT");
        AuthService service = new AuthService(props, mapper);

        assertEquals("VIEWER", service.login("viewer", "viewer-pass").role());
    }

    private AuthService service() {
        return new AuthService(props(), mapper);
    }

    private static BidPulseProperties props() {
        BidPulseProperties props = new BidPulseProperties();
        props.getAuth().setSecret("test-secret-please-change-me-32-bytes");
        props.getAuth().setTokenTtlSeconds(3600);

        Map<String, BidPulseProperties.AuthUser> users = new LinkedHashMap<>();
        users.put("admin", user("admin-pass", "ADMIN"));
        users.put("operator", user("operator-pass", "OPERATOR"));
        users.put("viewer", user("viewer-pass", "VIEWER"));
        props.getAuth().setUsers(users);
        return props;
    }

    private static BidPulseProperties.AuthUser user(String password, String role) {
        BidPulseProperties.AuthUser user = new BidPulseProperties.AuthUser();
        user.setPassword(password);
        user.setRole(role);
        return user;
    }
}
