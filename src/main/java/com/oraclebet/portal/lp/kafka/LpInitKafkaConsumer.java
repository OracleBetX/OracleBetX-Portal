package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.accountengine.api.AccountEngineUserApi;
import com.oraclebet.accountengine.api.dto.AccountEngineSignUpCommand;
import com.oraclebet.accountengine.api.dto.AccountEngineUserDto;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaConsumerThread;
import com.oraclebet.common.config.kafka.TradingKafkaRecordDecoder;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
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
import java.util.List;

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

    private final AccountEngineUserApi userApi;
    private final LpInitService lpInitService;
    private final MongoTemplate mongoTemplate;

    public LpInitKafkaConsumer(KafkaConsumer<String, byte[]> consumer,
                               KafkaProperties properties,
                               TradingKafkaTopics topics,
                               TradingKafkaRecordDecoder<LpInitCommand> decoder,
                               AccountEngineUserApi userApi,
                               LpInitService lpInitService,
                               MongoTemplate mongoTemplate) {
        super(consumer, log, properties, topics, decoder);
        this.userApi = userApi;
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
     * 创建 bot 用户（幂等）：先查是否已存在，不存在则注册。
     */
    private String ensureBotUser(String eventId, String marketId, String selectionId) {
        String email = botEmail(eventId, marketId, selectionId);

        // 先查
        return userApi.findByEmail(email)
                .map(AccountEngineUserDto::getUserId)
                .orElseGet(() -> {
                    // 不存在则创建
                    AccountEngineSignUpCommand signUp = new AccountEngineSignUpCommand();
                    signUp.setEmail(email);
                    signUp.setPassword("123456");
                    signUp.setCode("123456");

                    AccountEngineUserDto created = userApi.signUp(signUp);
                    log.info("[lp-init-consumer] bot user created email={} userId={}",
                            email, created.getUserId());
                    return created.getUserId();
                });
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
