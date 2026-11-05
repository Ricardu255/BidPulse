package com.bidpulse.testsupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 守卫规则本身的纯单测，不需要中间件。 */
class IntegrationTestGuardTest {

    @Test
    void acceptsLocalItDatabase() {
        assertTrue(IntegrationTestGuard.isValidItJdbcUrl(
                "jdbc:mysql://127.0.0.1:3306/bidpulse_it?useSSL=false"));
        assertTrue(IntegrationTestGuard.isValidItJdbcUrl(
                "jdbc:mysql://localhost:3306/foo_it"));
    }

    @Test
    void rejectsDemoRemoteAndNonSuffixDatabases() {
        assertFalse(IntegrationTestGuard.isValidItJdbcUrl(
                "jdbc:mysql://127.0.0.1:3306/bidpulse"));
        assertFalse(IntegrationTestGuard.isValidItJdbcUrl(
                "jdbc:mysql://db.example.com:3306/bidpulse_it"));
        assertFalse(IntegrationTestGuard.isValidItJdbcUrl(
                "jdbc:mysql://127.0.0.1:3306/bidpulse_it_backup"));
        assertFalse(IntegrationTestGuard.isValidItJdbcUrl(null));
    }

    @Test
    void redisDatabaseMustBeNonZeroAndInRange() {
        assertTrue(IntegrationTestGuard.isIsolatedRedisDatabase("1"));
        assertTrue(IntegrationTestGuard.isIsolatedRedisDatabase("15"));
        assertFalse(IntegrationTestGuard.isIsolatedRedisDatabase("0"));
        assertFalse(IntegrationTestGuard.isIsolatedRedisDatabase("16"));
        assertFalse(IntegrationTestGuard.isIsolatedRedisDatabase("abc"));
        assertFalse(IntegrationTestGuard.isIsolatedRedisDatabase(null));
    }

    @Test
    void kafkaTopicsMustUseItPrefix() {
        assertTrue(IntegrationTestGuard.isIsolatedKafkaTopic("bidpulse_it_bid_event"));
        assertTrue(IntegrationTestGuard.isIsolatedKafkaTopic("bidpulse_it_bid_event.DLT"));
        assertFalse(IntegrationTestGuard.isIsolatedKafkaTopic("bid_event"));
        assertFalse(IntegrationTestGuard.isIsolatedKafkaTopic(null));
    }

    @Test
    void fullTestsProfileFlagComesFromSurefireSystemProperty() {
        String previous = System.getProperty("bidpulse.full-tests");
        try {
            System.setProperty("bidpulse.full-tests", "true");
            assertTrue(IntegrationTestGuard.isFullTestsProfile());
            System.setProperty("bidpulse.full-tests", "false");
            assertFalse(IntegrationTestGuard.isFullTestsProfile());
            System.clearProperty("bidpulse.full-tests");
            assertFalse(IntegrationTestGuard.isFullTestsProfile());
        } finally {
            if (previous == null) {
                System.clearProperty("bidpulse.full-tests");
            } else {
                System.setProperty("bidpulse.full-tests", previous);
            }
        }
    }
}
