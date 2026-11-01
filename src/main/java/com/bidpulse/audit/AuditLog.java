package com.bidpulse.audit;

import lombok.Data;

import java.time.LocalDateTime;

/** 一条管理端/控制台写操作审计记录。 */
@Data
public class AuditLog {
    private Long id;
    private String actor;
    private String role;
    private String method;
    private String path;
    private Integer status;
    private String ip;
    private String detail;
    private LocalDateTime createdAt;
}
