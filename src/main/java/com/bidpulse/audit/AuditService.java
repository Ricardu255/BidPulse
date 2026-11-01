package com.bidpulse.audit;

import com.bidpulse.infra.persistence.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计写入是 best-effort：审计表不可用时只记日志，不能让已经成功的业务操作回滚/报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_PATH = 255;
    private static final int MAX_DETAIL = 512;

    private final AuditLogMapper mapper;

    public void record(String actor, String role, String method, String path,
                       int status, String ip, String detail) {
        try {
            AuditLog log = new AuditLog();
            log.setActor(trim(actor, 64, "anonymous"));
            log.setRole(trim(role, 32, "ANONYMOUS"));
            log.setMethod(trim(method, 8, "-"));
            log.setPath(trim(path, MAX_PATH, "-"));
            log.setStatus(status);
            log.setIp(trim(ip, 64, "-"));
            log.setDetail(trim(detail, MAX_DETAIL, ""));
            mapper.insert(log);
        } catch (Exception e) {
            // 审计失败不阻塞业务；运维可通过应用日志发现 audit_log 表/连接异常
            log.warn("审计写入失败 actor={} {} {}: {}", actor, method, path, e.getMessage());
        }
    }

    public List<AuditLog> recent(int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        return mapper.recent(cap);
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String trim(String raw, int max, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
