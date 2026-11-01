package com.bidpulse.auth;

/** 已认证主体：只放展示与鉴权需要的最小字段，不携带密码。 */
public record AuthPrincipal(String username, Role role) {
}
