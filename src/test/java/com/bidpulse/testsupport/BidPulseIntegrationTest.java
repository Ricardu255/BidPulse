package com.bidpulse.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试组合注解：统一打 {@code integration} tag，并挂上隔离守卫。
 *
 * <p>用法：
 * <pre>{@code
 * @SpringBootTest
 * @BidPulseIntegrationTest
 * class SomeIntegrationTest { ... }
 * }</pre>
 *
 * <p>不要在这些测试上再写裸 {@code @Tag("integration")}；组合注解已经包含。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("integration")
@ExtendWith(IntegrationTestGuard.class)
@Import(IntegrationDataSourceGuard.class)
public @interface BidPulseIntegrationTest {
}
