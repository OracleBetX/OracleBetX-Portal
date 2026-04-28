package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Properties;

/**
 * LP Init Kafka Consumer 配置：
 * 启动一个后台线程消费 lp.init.command topic。
 * <p>
 * 通过 kafka.topics.lp-init 配置开启（默认 topic 名 "lp.init.command"）。
 * 设置 lp.init.consumer.enabled=false 可关闭。
 */
@Configuration
@ConditionalOnProperty(name = "lp.init.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class LpInitKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(LpInitKafkaConfig.class);

    private LpInitKafkaConsumer consumerThread;

    public LpInitKafkaConfig(KafkaProperties kafkaProperties,
                             TradingKafkaTopics topics,
                             ObjectMapper objectMapper,
                             GatewayAddressProvider gatewayAddressProvider,
                             RestTemplate nodeRpcRestTemplate,
                             MongoTemplate mongoTemplate,
                             LpInitStateRepository lpInitStateRepository,
                             @Value("${lpbot.base-url:http://localhost:10020}") String lpbotBaseUrl) {
        try {
            KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                    buildConsumerProperties(kafkaProperties));

            LpInitKafkaConsumer.LpInitCommandDecoder decoder =
                    new LpInitKafkaConsumer.LpInitCommandDecoder(objectMapper);

            this.consumerThread = new LpInitKafkaConsumer(
                    consumer, kafkaProperties, topics, decoder,
                    gatewayAddressProvider, nodeRpcRestTemplate, mongoTemplate,
                    lpInitStateRepository, lpbotBaseUrl);
            this.consumerThread.setName("lp-init-consumer");
            this.consumerThread.setDaemon(true);
            this.consumerThread.start();

            log.info("[lp-init-kafka] consumer thread started, topic={}", topics.lpInit());
        } catch (Exception e) {
            log.error("[lp-init-kafka] failed to start consumer: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumerThread != null) {
            log.info("[lp-init-kafka] shutting down consumer...");
            consumerThread.shutdown();
        }
    }

    private Properties buildConsumerProperties(KafkaProperties kafkaProperties) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put("security.protocol", kafkaProperties.getSecurityProtocol());
        props.put("sasl.mechanism", kafkaProperties.getSaslMechanism());
        props.put("sasl.jaas.config", kafkaProperties.getSaslJaasConfig());

        props.put(ConsumerConfig.GROUP_ID_CONFIG, "lp-init-portal");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000"); // 5 min
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");

        return props;
    }
}
