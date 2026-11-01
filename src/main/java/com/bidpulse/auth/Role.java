package com.bidpulse.auth;

/**
 * 控制台角色。角色是粗粒度权限，不替代业务校验：
 * <ul>
 *   <li>{@link #VIEWER}：只读概览、广告、预算、运维、数据与审计之外的页面；</li>
 *   <li>{@link #OPERATOR}：在 VIEWER 基础上可执行控制台写操作（投放、实验、DLT、对账）；</li>
 *   <li>{@link #ADMIN}：在 OPERATOR 基础上可操作 /admin/**、Actuator 指标并查看审计日志。</li>
 * </ul>
 */
public enum Role {
    VIEWER,
    OPERATOR,
    ADMIN;

    /** Spring Security 的 authority 约定：ROLE_ 前缀 */
    public String authority() {
        return "ROLE_" + name();
    }

    /** 大小写不敏感解析；非法值返回 null，由调用方决定降级或拒绝 */
    public static Role from(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
