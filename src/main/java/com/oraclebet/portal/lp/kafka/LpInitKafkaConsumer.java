package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaConsumerThread;
import com.oraclebet.common.config.kafka.TradingKafkaRecordDecoder;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import com.oraclebet.portal.lp.dto.LpInitCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Kafka Consumer：异步处理 LP 初始化。
 * <p>
 * 每条消息对应一个 market（home + away 两个 selection），处理流程：
 * 1. 调网关转发到 AccountEngine init-lp-v2：创建用户 → 持仓初始化 → Redis 同步
 * 2. 写 bot_product_binding（MongoDB）
 * <p>
 * 失败自动进 DLQ。
 */
public class LpInitKafkaConsumer extends TradingKafkaConsumerThread<LpInitCommand> {

    private static final Logger log = LoggerFactory.getLogger(LpInitKafkaConsumer.class);
    private static final String BOT_BINDING_COLLECTION = "bot_product_binding";

    private final GatewayAddressProvider gatewayAddressProvider;
    private final RestTemplate restTemplate;
    private final MongoTemplate mongoTemplate;

    public LpInitKafkaConsumer(KafkaConsumer<String, byte[]> consumer,
                               KafkaProperties properties,
                               TradingKafkaTopics topics,
                               TradingKafkaRecordDecoder<LpInitCommand> decoder,
                               GatewayAddressProvider gatewayAddressProvider,
                               RestTemplate restTemplate,
                               MongoTemplate mongoTemplate) {
        super(consumer, log, properties, topics, decoder);
        this.gatewayAddressProvider = gatewayAddressProvider;
        this.restTemplate = restTemplate;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    protected void subscribeTopics() {
        consumer.subscribe(List.of(topics().lpInit()));
        log.info("[lp-init-consumer] subscribed to topic={}", topics().lpInit());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleDecodedRecord(ConsumerRecord<String, byte[]> rawRecord, LpInitCommand cmd) throws Exception {
        log.info("[lp-init-consumer] processing eventId={} marketId={}", cmd.getEventId(), cmd.getMarketId());

        // 1. Home: 创建用户 + 持仓初始化 + Redis 同步（AccountEngine 内部完成）
        Map<String, String> homeResult = callInitLpV2(cmd.getEventId(), cmd.getMarketId(),
                cmd.getHomeSelectionId(), cmd.getHomeQty(), cmd.getHomePrice());
        String homeUserId = homeResult.get("userId");
        log.info("[lp-init-consumer] home done userId={} selectionId={}", homeUserId, cmd.getHomeSelectionId());

        // 2. Away: 创建用户 + 持仓初始化 + Redis 同步
        Map<String, String> awayResult = callInitLpV2(cmd.getEventId(), cmd.getMarketId(),
                cmd.getAwaySelectionId(), cmd.getAwayQty(), cmd.getAwayPrice());
        String awayUserId = awayResult.get("userId");
        log.info("[lp-init-consumer] away done userId={} selectionId={}", awayUserId, cmd.getAwaySelectionId());

        // 3. 写 bot_product_binding（MongoDB）
        upsertBotBinding(cmd.getEventId(), cmd.getMarketId(), cmd.getHomeSelectionId(), homeUserId);
        upsertBotBinding(cmd.getEventId(), cmd.getMarketId(), cmd.getAwaySelectionId(), awayUserId);

        log.info("[lp-init-consumer] done eventId={} marketId={}", cmd.getEventId(), cmd.getMarketId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> callInitLpV2(String eventId, String marketId, String selectionId,
                                              BigDecimal qty, BigDecimal price) {
        String url = gatewayAddressProvider.getGatewayUrl() + "/api/account/lp/init-lp-v2?"
                + "eventId=" + enc(eventId)
                + "&marketId=" + enc(marketId)
                + "&selectionId=" + enc(selectionId)
                + "&qty=" + qty.toPlainString()
                + "&price=" + price.toPlainString();
        return restTemplate.postForObject(url, null, Map.class);
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void upsertBotBinding(String eventId, String marketId,
                                   String selectionId, String accountId) {
        if (selectionId == null || selectionId.isBlank()) return;

        try {
            String sid = eventId + "_" + marketId + "_" + selectionId;
            long now = System.currentTimeMillis();

            Query q = Query.query(Criteria.where("sid").is(sid));
            Update u = new Update()
                    .set("sid", sid)
                    .set("fixtureId", eventId)
                    .set("marketId", marketId)
                    .set("selectionId", selectionId)
                    .set("accountId", accountId)
                    .set("status", "ACTIVE")
                    .set("updatedAt", now)
                    .setOnInsert("createdAt", now);
            mongoTemplate.upsert(q, u, BOT_BINDING_COLLECTION);
        } catch (Exception e) {
            log.warn("[lp-init-consumer] failed to upsert bot_product_binding: {}", e.getMessage());
        }
    }

    /**
     * Jackson decoder for LpInitCommand.
     */
    public static class LpInitCommandDecoder implements TradingKafkaRecordDecoder<LpInitCommand> {
        private final ObjectMapper objectMapper;

        public LpInitCommandDecoder(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public LpInitCommand decode(String topic, byte[] payload) throws Exception {
            if (payload == null || payload.length == 0) return null;
            return objectMapper.readValue(payload, LpInitCommand.class);
        }
    }
}
