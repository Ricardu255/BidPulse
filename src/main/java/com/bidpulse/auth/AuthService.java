package com.bidpulse.auth;

import com.bidpulse.api.BizException;
import com.bidpulse.config.BidPulseProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 无状态 Bearer token 认证。
 *
 * <p>用户名/密码只在登录时校验一次；之后请求携带 HMAC-SHA256 签名的 token，
 * 服务端不存 session。token 载荷包含用户名、角色和过期时间，签名覆盖全部字段，
 * 因此客户端无法把 VIEWER 改成 ADMIN。
 *
 * <p><b>密码来源</b>：{@code application.yml} 只声明账号和角色，密码通过
 * {@code BIDPULSE_*_PASSWORD} 环境变量注入；未配置时本地启动生成一次性随机密码并提示到日志。
 * 仓库中不保存任何真实密码，启动后也只保留 BCrypt 哈希。
 */
@Slf4j
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char[] PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 未知用户也走一次 BCrypt，避免通过响应时间枚举用户名 */
    private final String dummyHash;

    private final Map<String, Account> accounts;
    private final byte[] secret;
    private final long ttlSeconds;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ObjectMapper objectMapper;

    public AuthService(BidPulseProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dummyHash = encoder.encode("not-a-real-password");

        BidPulseProperties.Auth cfg = props.getAuth();
        this.ttlSeconds = Math.max(60, cfg.getTokenTtlSeconds());
        this.secret = resolveSecret(cfg.getSecret());
        this.accounts = buildAccounts(cfg.getUsers());

        if (accounts.isEmpty()) {
            log.warn("bidpulse.auth.users 为空，控制台将无法登录");
        }
    }

    /** 登录成功返回 token；用户名不存在或密码错误统一返回 401，不区分具体原因。 */
    public LoginResult login(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        Account account = accounts.get(name);
        String hash = account == null ? dummyHash : account.passwordHash();
        boolean matched = encoder.matches(rawPassword == null ? "" : rawPassword, hash);
        if (account == null || !matched) {
            throw BizException.unauthorized("用户名或密码错误");
        }

        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String token = issue(name, account.role(), expiresAt);
        return new LoginResult(token, name, account.role().name(),
                Instant.ofEpochSecond(expiresAt).toString());
    }

    /** 验签、解析并检查过期时间；任何一步失败都返回 empty，由安全层统一转 401。 */
    public Optional<AuthPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payloadPart = token.substring(0, dot);
        String signaturePart = token.substring(dot + 1);

        try {
            byte[] expected = sign(payloadPart);
            byte[] actual = Base64.getUrlDecoder().decode(signaturePart);
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            TokenPayload payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(payloadPart), TokenPayload.class);
            if (payload == null || payload.exp() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            Role role = Role.from(payload.role());
            if (role == null || payload.sub() == null || payload.sub().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AuthPrincipal(payload.sub(), role));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Map<String, Account> buildAccounts(Map<String, BidPulseProperties.AuthUser> users) {
        Map<String, Account> out = new LinkedHashMap<>();
        if (users == null) {
            return out;
        }
        users.forEach((username, user) -> {
            if (username == null || username.isBlank() || user == null) {
                return;
            }
            Role role = Role.from(user.getRole());
            if (role == null) {
                log.warn("账号 {} 的角色 {} 非法，降级为 VIEWER", username, user.getRole());
                role = Role.VIEWER;
            }
            String raw = user.getPassword();
            if (raw == null || raw.isBlank()) {
                raw = randomPassword();
                log.warn("账号 {} 未配置密码，已生成一次性演示密码：{}（仅本地演示；生产请设置环境变量）",
                        username, raw);
            }
            out.put(username, new Account(encoder.encode(raw), role));
        });
        return out;
    }

    private byte[] resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        byte[] generated = new byte[32];
        RANDOM.nextBytes(generated);
        log.warn("未配置 BIDPULSE_AUTH_SECRET，已生成启动时随机密钥；重启后所有 token 失效");
        return generated;
    }

    private String issue(String username, Role role, long expiresAt) {
        try {
            String json = objectMapper.writeValueAsString(new TokenPayload(username, role.name(), expiresAt));
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException("签发登录 token 失败", e);
        }
    }

    private byte[] sign(String payload) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private record Account(String passwordHash, Role role) {
    }

    private record TokenPayload(String sub, String role, long exp) {
    }

    public record LoginResult(String token, String username, String role, String expiresAt) {
    }
}
