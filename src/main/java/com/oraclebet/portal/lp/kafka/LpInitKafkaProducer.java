package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaProducer;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
import com.oraclebet.portal.lp.dto.LpInitCommand;
import com.oraclebet.support.messaging.kafka.KafkaHeaderNames;
import com.oraclebet.support.messaging.kafka.KafkaPublishRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LpInitKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(LpInitKafkaProducer.class);

    private final KafkaProperties kafkaProperties;
    private final TradingKafkaTopics topics;
    private final ObjectMapper objectMapper;

    private TradingKafkaProducer<byte[]> producer;

    public LpInitKafkaProducer(KafkaProperties kafkaProperties,
                               TradingKafkaTopics topics,
                               ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.topics = topics;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.producer = new TradingKafkaProducer<>(kafkaProperties, new ByteArraySerializer());
        log.info("[lp-init-producer] initialized, topic={}", topics.lpInit());
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.close();
        }
    }

    /** 显式 flush — controller 在批量 send 完后调一次，确保返回前所有消息已经投递到 Kafka broker。 */
    public void flush() {
        if (producer != null) {
            producer.flush();
        }
    }

    public void send(LpInitCommand cmd) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cmd);
            String key = cmd.getEventId() + ":" + cmd.getMarketId();

            KafkaPublishRequest<byte[]> request = new KafkaPublishRequest<>(
                    topics.lpInit(), key, payload
            );
            if (cmd.getTraceId() != null) {
                request.header(KafkaHeaderNames.TRACE_ID, cmd.getTraceId());
            }
            request.header(KafkaHeaderNames.MESSAGE_KIND, "LP_INIT");
            request.header(KafkaHeaderNames.EVENT_ID, cmd.getEventId());

            producer.send(request, (metadata, exception) -> {
                if (exception != null) {
                    log.error("[lp-init-producer] send failed eventId={} marketId={}: {}",
                            cmd.getEventId(), cmd.getMarketId(), exception.getMessage());
                } else {
                    log.info("[lp-init-producer] sent eventId={} marketId={} partition={} offset={}",
                            cmd.getEventId(), cmd.getMarketId(),
                            metadata.partition(), metadata.offset());
                }
            });
            // 不在每条 send 后 flush — flush 会强制同步等待，让 controller 串行阻塞 ~2s/条。
            // 改由调用方（initV2 controller）在 batch 末尾统一 flush 一次。
        } catch (Exception e) {
            log.error("[lp-init-producer] serialize/send error eventId={} marketId={}: {}",
                    cmd.getEventId(), cmd.getMarketId(), e.getMessage(), e);
            throw new RuntimeException("LP init kafka send failed", e);
        }
    }
}
