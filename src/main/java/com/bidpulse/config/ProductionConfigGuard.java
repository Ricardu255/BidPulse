package com.bidpulse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code prod} profile 的启动守卫：缺少密钥时直接失败，而不是退回随机密码/空密码。
 *
 * <p>本地 demo 允许 {@code AuthService} 在未配置密码时生成一次性随机值并把提示打到日志；
 * 这在生产是危险默认值：重启后 token 全失效、日志里出现凭据、空密码可能被误当成合法配置。
 * 因此生产 profile 把「密钥必须显式注入」变成启动不变量，而不是运行期警告。
 *
 * <p>密钥来源建议：容器编排的 secret 挂载（{@code /run/secrets/...}）或只读环境变量；
 * 不要把真实值写进 {@code .env} 后提交。
 */
@Configuration
@Profile("prod")
public class ProductionConfigGuard {

    public ProductionConfigGuard(BidPulseProperties props, Environment env) {
        List<String> missing = missingSecrets(props, env.getProperty("spring.datasource.password"));
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "prod profile 缺少必需密钥，拒绝启动：" + String.join(", ", missing)
                            + "。请通过环境变量或 /run/secrets 注入后再启动。");
        }
    }

    /**
     * 返回缺失的密钥名；空列表表示可以启动。
     *
     * <p>包级可见是为了让单元测试直接覆盖，不必启动完整的 Spring 上下文。
     */
    static List<String> missingSecrets(BidPulseProperties props, String dbPassword) {
        List<String> missing = new ArrayList<>();
        if (isBlank(props.getAuth().getSecret())) {
            missing.add("BIDPULSE_AUTH_SECRET");
        }
        if (isBlank(dbPassword)) {
            missing.add("BIDPULSE_DB_PASSWORD（或 MYSQL_PASSWORD）");
        }

        Map<String, BidPulseProperties.AuthUser> users = props.getAuth().getUsers();
        if (users == null || users.isEmpty()) {
            missing.add("bidpulse.auth.users（至少配置一个账号）");
            return missing;
        }
        users.forEach((name, user) -> {
            if (user == null || isBlank(user.getPassword())) {
                missing.add("BIDPULSE_" + name.toUpperCase().replace('-', '_') + "_PASSWORD");
            }
        });
        return missing;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
