package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaConsumerThread;
import com.oraclebet.common.config.kafka.TradingKafkaRecordDecoder;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
import com.oraclebet.discovery.model.DiscoveryNodeType;
import com.oraclebet.discovery.nacos.rpc.NodeRpcClient;
import com.oraclebet.portal.lp.dto.LpInitCommand;
import com.oraclebet.portal.lp.dto.LpInitRequest;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.service.LpInitService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka Consumer：异步处理 LP 初始化。
 * <p>
 * 每条消息对应一个 market，处理流程：
 * 1. 创建 bot 用户（幂等）
 * 2. 调 lpInitService.initLp()（CREDIT → RESERVE → COMMIT → initInventory → DONE）
 * 3. 写 bot_product_binding（MongoDB）
 * <p>
 * 失败自动进 DLQ。
 */
public class LpInitKafkaConsumer extends TradingKafkaConsumerThread<LpInitCommand> {

    private static final Logger log = LoggerFactory.getLogger(LpInitKafkaConsumer.class);
    private static final String BOT_BINDING_COLLECTION = "bot_product_binding";

    private final NodeRpcClient nodeRpcClient;
    private final LpInitService lpInitService;
    private final MongoTemplate mongoTemplate;

    public LpInitKafkaConsumer(KafkaConsumer<String, byte[]> consumer,
                               KafkaProperties properties,
                               TradingKafkaTopics topics,
                               TradingKafkaRecordDecoder<LpInitCommand> decoder,
                               NodeRpcClient nodeRpcClient,
                               LpInitService lpInitService,
                               MongoTemplate mongoTemplate) {
        super(consumer, log, properties, topics, decoder);
        this.nodeRpcClient = nodeRpcClient;
        this.lpInitService = lpInitService;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    protected void subscribeTopics() {
        consumer.subscribe(List.of(topics().lpInit()));
        log.info("[lp-init-consumer] subscribed to topic={}", topics().lpInit());
    }

    @Override
    protected void handleDecodedRecord(ConsumerRecord<String, byte[]> rawRecord, LpInitCommand cmd) throws Exception {
        log.info("[lp-init-consumer] processing eventId={} marketId={}", cmd.getEventId(), cmd.getMarketId());

        // 1. 创建 bot 用户（home 和 away 共用同一个 bot 用户，取 home 的 email 对应的用户）
        String lpUserId = ensureBotUser(cmd.getEventId(), cmd.getMarketId(), cmd.getHomeSelectionId());
        if (lpUserId == null || lpUserId.isBlank()) {
            throw new RuntimeException("Failed to create/find bot user for eventId=" + cmd.getEventId()
                    + " marketId=" + cmd.getMarketId());
        }

        log.info("[lp-init-consumer] bot user ready lpUserId={} eventId={} marketId={}",
                lpUserId, cmd.getEventId(), cmd.getMarketId());

        // 2. 构建 LpInitRequest，调用现有 initLp 逻辑
        LpInitRequest initReq = new LpInitRequest();
        initReq.setLpUserId(lpUserId);
        initReq.setEventId(cmd.getEventId());
        initReq.setMarketId(cmd.getMarketId());
        initReq.setHomeSelectionId(cmd.getHomeSelectionId());
        initReq.setHomePrice(cmd.getHomePrice());
        initReq.setHomeQty(cmd.getHomeQty());
        initReq.setAwaySelectionId(cmd.getAwaySelectionId());
        initReq.setAwayPrice(cmd.getAwayPrice());
        initReq.setAwayQty(cmd.getAwayQty());
        initReq.setInitCash(cmd.getInitCash() != null ? cmd.getInitCash() : BigDecimal.ZERO);

        LpInitStateEntity state = lpInitService.initLp(initReq);

        // 3. 写 bot_product_binding
        if (state.getStatus() == LpInitStateEntity.Status.DONE) {
            upsertBotBinding(cmd.getEventId(), cmd.getMarketId(),
                    cmd.getHomeSelectionId(), lpUserId);
            upsertBotBinding(cmd.getEventId(), cmd.getMarketId(),
                    cmd.getAwaySelectionId(), lpUserId);
        }

        log.info("[lp-init-consumer] done eventId={} marketId={} status={}",
                cmd.getEventId(), cmd.getMarketId(), state.getStatus());
    }

    /**
     * 创建 bot 用户（幂等）：通过 Auth 节点 /api/users 注册，然后查询 userId。
     */
    @SuppressWarnings("unchecked")
    private String ensureBotUser(String eventId, String marketId, String selectionId) {
        String email = botEmail(eventId, marketId, selectionId);

        // 1. 先通过 Auth 注册（幂等，已存在会返回已有用户）
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", email);
            body.put("password", "123456");
            Map<String, Object> result = nodeRpcClient.post(
                    DiscoveryNodeType.AUTH_NODE, "/api/users", body, Map.class);
            log.info("[lp-init-consumer] auth sign-up result email={} result={}", email, result);
        } catch (Exception e) {
            log.warn("[lp-init-consumer] auth sign-up failed (may already exist) email={} error={}",
                    email, e.getMessage());
        }

        // 2. 查询用户 userId
        try {
            Map<String, Object> userInfo = nodeRpcClient.get(
                    DiscoveryNodeType.AUTH_NODE,
                    "/api/users/self?email=" + email, Map.class);
            if (userInfo != null) {
                Object data = userInfo.getOrDefault("data", userInfo);
                if (data instanceof Map) {
                    Object userId = ((Map<String, Object>) data).get("id");
                    if (userId != null) {
                        log.info("[lp-init-consumer] bot user ready email={} userId={}", email, userId);
                        return userId.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.error("[lp-init-consumer] failed to query bot user email={}", email, e);
        }

        return null;
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

    private String botEmail(String eventId, String marketId, String outcomeId) {
        return safe(eventId) + "_" + safe(marketId) + "_" + safe(outcomeId) + "_bot@xbet.com";
    }

    private String safe(String s) {
        return s == null ? "null" : s.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
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
