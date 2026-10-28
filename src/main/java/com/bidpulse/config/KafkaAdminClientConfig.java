package com.bidpulse.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 共享 Kafka {@link AdminClient}。
 *
 * <p>{@code AdminClient} 是重量级对象（自带网络线程、元数据缓存）。管理端每次
 * lag 查询都 {@code AdminClient.create(...)} 一个再 close，会反复建连/拉元数据，
 * 高并发或频繁调用下既浪费资源又容易把管理端拖慢。这里做成单例，
 * 容器关闭时调用其 {@code close()}。
 *
 * <p>超时收紧到 5s：管理端接口不应像业务链路那样无限等待 broker。
 */
@Configuration
public class KafkaAdminClientConfig {

    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        return AdminClient.create(Map.of(
                "bootstrap.servers", bootstrap,
                "default.api.timeout.ms", 5000,
                "request.timeout.ms", 5000));
    }
}
