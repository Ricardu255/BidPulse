package com.bidpulse.audit;

import com.bidpulse.auth.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 统一记录控制台/管理端写操作。
 *
 * <p>在安全过滤器之后执行，写入前先抓取认证主体（SecurityContext 在返回后会被清理）。
 * 只记录非 GET/HEAD/OPTIONS；登录接口单独在 {@code ConsoleAuthController} 记录，
 * 避免把登录请求体里的密码写进审计。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuditLogFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/console-api/v1/auth/login";

    private final AuditService auditService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/console-api/") && !path.startsWith("/admin/")) {
            return true;
        }
        if (LOGIN_PATH.equals(path)) {
            return true;
        }
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuthPrincipal principal = currentPrincipal();
        try {
            filterChain.doFilter(request, response);
        } finally {
            String actor = principal == null ? "anonymous" : principal.username();
            String role = principal == null ? "ANONYMOUS" : principal.role().name();
            auditService.record(actor, role, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), AuditService.clientIp(request), request.getQueryString());
        }
    }

    private static AuthPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return principal;
        }
        return null;
    }
}
