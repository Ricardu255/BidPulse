package com.bidpulse.auth;

import com.bidpulse.audit.AuditLogFilter;
import com.bidpulse.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuditLogFilterTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditLogFilter filter = new AuditLogFilter(auditService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("写请求记录操作人、路径、状态与 IP")
    void recordsWriteWithPrincipal() throws Exception {
        authenticate(new AuthPrincipal("operator", Role.OPERATOR));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/console-api/v1/experiments");
        request.setRemoteAddr("127.0.0.1");
        request.setQueryString("x=1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, (req, res) -> { });

        verify(auditService).record("operator", "OPERATOR", "PUT",
                "/console-api/v1/experiments", 200, "127.0.0.1", "x=1");
    }

    @Test
    @DisplayName("GET 与公开 /bid 不写审计")
    void skipsReadsAndBid() throws Exception {
        authenticate(new AuthPrincipal("viewer", Role.VIEWER));
        filter.doFilter(new MockHttpServletRequest("GET", "/console-api/v1/overview"),
                new MockHttpServletResponse(), (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest("POST", "/bid"),
                new MockHttpServletResponse(), (req, res) -> { });

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("登录接口不进入通用审计，避免记录密码")
    void skipsLoginPath() throws Exception {
        filter.doFilter(new MockHttpServletRequest("POST", "/console-api/v1/auth/login"),
                new MockHttpServletResponse(), (req, res) -> { });

        verifyNoInteractions(auditService);
    }

    private static void authenticate(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority(principal.role().authority()))));
    }
}
