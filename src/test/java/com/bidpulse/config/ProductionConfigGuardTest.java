package com.bidpulse.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** prod profile 的密钥完整性检查：缺任何一项都必须拒绝启动。 */
class ProductionConfigGuardTest {

    @Test
    void reportsMissingAuthSecretDbPasswordAndUserPassword() {
        BidPulseProperties props = new BidPulseProperties();
        props.getAuth().getUsers().put("admin", user(""));

        List<String> missing = ProductionConfigGuard.missingSecrets(props, "");

        assertTrue(missing.contains("BIDPULSE_AUTH_SECRET"));
        assertTrue(missing.contains("BIDPULSE_DB_PASSWORD（或 MYSQL_PASSWORD）"));
        assertTrue(missing.contains("BIDPULSE_ADMIN_PASSWORD"));
    }

    @Test
    void reportsMissingUserList() {
        BidPulseProperties props = new BidPulseProperties();
        props.getAuth().setSecret("configured-secret");

        List<String> missing = ProductionConfigGuard.missingSecrets(props, "db-password");

        assertTrue(missing.stream().anyMatch(m -> m.contains("bidpulse.auth.users")));
    }

    @Test
    void passesWhenAllSecretsAreConfigured() {
        BidPulseProperties props = new BidPulseProperties();
        props.getAuth().setSecret("configured-secret");
        props.getAuth().getUsers().put("admin", user("admin-password"));

        assertTrue(ProductionConfigGuard.missingSecrets(props, "db-password").isEmpty());
    }

    private BidPulseProperties.AuthUser user(String password) {
        BidPulseProperties.AuthUser user = new BidPulseProperties.AuthUser();
        user.setPassword(password);
        return user;
    }
}
