package com.bidpulse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 生产者可靠性语义的测试锁：{@code acks=all} 与幂等生产者必须同时存在。
 *
 * <p>幂等生产者要求 acks=all（改成 1 会启动失败），但仍值得把契约写成测试：
 * 本地调试时「顺手把 acks 改成 1 看延迟」这类改动不该进入仓库。
 * 直接读 classpath 的 application.yml，不启动 Spring 上下文，跑得足够快。
 */
class KafkaProducerSemanticsTest {

    @Test
    void applicationYml_keepsAcksAllWithIdempotentProducer() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        Object acks = null;
        Object idempotence = null;
        for (PropertySource<?> source : sources) {
            if (acks == null) {
                acks = source.getProperty("spring.kafka.producer.acks");
            }
            if (idempotence == null) {
                idempotence = source.getProperty("spring.kafka.producer.properties.enable.idempotence");
            }
        }

        assertEquals("all", acks,
                "acks 必须是 all：幂等生产者要求 acks=all，也是 DLT/重放语义的前提");
        assertEquals(Boolean.TRUE, idempotence,
                "enable.idempotence 必须为 true：防止 broker 侧重复写入");
    }
}
