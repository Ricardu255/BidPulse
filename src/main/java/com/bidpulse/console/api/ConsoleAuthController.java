package com.bidpulse.console.api;

import com.bidpulse.api.ApiResponse;
import com.bidpulse.api.BizException;
import com.bidpulse.audit.AuditService;
import com.bidpulse.auth.AuthPrincipal;
import com.bidpulse.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 控制台登录入口：用户名/密码换 Bearer token，之后由 {@code TokenAuthFilter} 验签。
 *
 * <p>登录成功/失败都写审计；失败只记录用户名，不记录密码。
 */
@RestController
@RequestMapping("/console-api/v1/auth")
@ConditionalOnProperty(prefix = "bidpulse.console", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ConsoleAuthController {

    private final AuthService authService;
    private final AuditService auditService;

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request,
                                                      HttpServletRequest http) {
        String username = request.username() == null ? "" : request.username().trim();
        try {
            AuthService.LoginResult result = authService.login(username, request.password());
            auditService.record(result.username(), result.role(), "LOGIN", http.getRequestURI(),
                    200, AuditService.clientIp(http), "username=" + username);
            return ApiResponse.ok(result);
        } catch (BizException e) {
            auditService.record("anonymous", "ANONYMOUS", "LOGIN", http.getRequestURI(),
                    e.getStatus(), AuditService.clientIp(http), "username=" + username);
            throw e;
        }
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return ApiResponse.ok(Map.of(
                "username", principal.username(),
                "role", principal.role().name()));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
