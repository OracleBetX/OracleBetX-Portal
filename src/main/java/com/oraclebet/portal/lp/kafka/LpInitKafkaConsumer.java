package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaConsumerThread;
import com.oraclebet.common.config.kafka.TradingKafkaRecordDecoder;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import com.oraclebet.portal.lp.dto.LpInitCommand;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
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
import java.time.Instant;
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
    private final LpInitStateRepository lpInitStateRepository;
    private final String lpbotBaseUrl;

    public LpInitKafkaConsumer(KafkaConsumer<String, byte[]> consumer,
                               KafkaProperties properties,
                               TradingKafkaTopics topics,
                               TradingKafkaRecordDecoder<LpInitCommand> decoder,
                               GatewayAddressProvider gatewayAddressProvider,
                               RestTemplate restTemplate,
                               MongoTemplate mongoTemplate,
                               LpInitStateRepository lpInitStateRepository,
                               String lpbotBaseUrl) {
        super(consumer, log, properties, topics, decoder);
        this.gatewayAddressProvider = gatewayAddressProvider;
        this.restTemplate = restTemplate;
        this.mongoTemplate = mongoTemplate;
        this.lpInitStateRepository = lpInitStateRepository;
        this.lpbotBaseUrl = (lpbotBaseUrl == null || lpbotBaseUrl.isBlank())
                ? "http://localhost:10020"
                : lpbotBaseUrl.replaceAll("/+$", "");
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

        String homeUserId = null;
        String awayUserId = null;
        try {
            // 1. Home: 创建用户 + 持仓初始化 + Redis 同步（AccountEngine 内部完成）
            Map<String, String> homeResult = callInitLpV2(cmd.getEventId(), cmd.getMarketId(),
                    cmd.getHomeSelectionId(), cmd.getHomeQty(), cmd.getHomePrice());
            homeUserId = homeResult.get("userId");
            log.info("[lp-init-consumer] home done userId={} selectionId={}", homeUserId, cmd.getHomeSelectionId());

            // 2. Away: 创建用户 + 持仓初始化 + Redis 同步
            Map<String, String> awayResult = callInitLpV2(cmd.getEventId(), cmd.getMarketId(),
                    cmd.getAwaySelectionId(), cmd.getAwayQty(), cmd.getAwayPrice());
            awayUserId = awayResult.get("userId");
            log.info("[lp-init-consumer] away done userId={} selectionId={}", awayUserId, cmd.getAwaySelectionId());

            // 3. 写 bot_product_binding（portal 自己一份，与 lpbot 端解耦）
            upsertBotBinding(cmd.getEventId(), cmd.getMarketId(), cmd.getHomeSelectionId(), homeUserId);
            upsertBotBinding(cmd.getEventId(), cmd.getMarketId(), cmd.getAwaySelectionId(), awayUserId);

            // 3b. 通知 lpbot 自己的 BotUserService 注册并登录两个 selection 对应的 bot
            //     这样 lpbot 才会写自己的 BotProductBindingRepository（带 _class），
            //     /admin/bots?fixtureId=xxx 才能列出这些 bot；否则 portal 写的记录 lpbot 不读。
            notifyLpBotLogin(cmd.getEventId(), cmd.getMarketId(), cmd.getHomeSelectionId());
            notifyLpBotLogin(cmd.getEventId(), cmd.getMarketId(), cmd.getAwaySelectionId());

            // 4. 写 lp_init_state (PostgreSQL exchange schema)，让 GET /api/lp/init-status 看得到进度
            //    Producer 已经预写两条 INITING 占位（pending-{traceId}-{marketId}-{h|a}），
            //    consumer 完成后把占位删掉，再写两条真实 lpUserId 的 DONE 记录。
            deletePendingState(cmd, "h");
            deletePendingState(cmd, "a");
            upsertInitState(homeUserId, cmd, LpInitStateEntity.Status.DONE, "OK");
            upsertInitState(awayUserId, cmd, LpInitStateEntity.Status.DONE, "OK");

            log.info("[lp-init-consumer] done eventId={} marketId={}", cmd.getEventId(), cmd.getMarketId());
        } catch (Exception e) {
            // 失败时把两条 pending 占位标记为 FAILED（保留可见进度）
            try {
                markPendingFailed(cmd, "h", e.getMessage());
                markPendingFailed(cmd, "a", e.getMessage());
            } catch (Exception ignore) {
                // best-effort
            }
            throw e;
        }
    }

    private void deletePendingState(LpInitCommand cmd, String slot) {
        if (cmd.getTraceId() == null) return;
        try {
            String pendingId = "pending-" + cmd.getTraceId() + "-" + cmd.getMarketId() + "-" + slot;
            lpInitStateRepository
                    .findByLpUserIdAndEventIdAndMarketId(pendingId, cmd.getEventId(), cmd.getMarketId())
                    .ifPresent(lpInitStateRepository::delete);
        } catch (Exception e) {
            log.debug("[lp-init-consumer] delete pending failed slot={} marketId={}: {}",
                    slot, cmd.getMarketId(), e.getMessage());
        }
    }

    private void markPendingFailed(LpInitCommand cmd, String slot, String message) {
        if (cmd.getTraceId() == null) return;
        try {
            String pendingId = "pending-" + cmd.getTraceId() + "-" + cmd.getMarketId() + "-" + slot;
            lpInitStateRepository
                    .findByLpUserIdAndEventIdAndMarketId(pendingId, cmd.getEventId(), cmd.getMarketId())
                    .ifPresent(entity -> {
                        entity.setStatus(LpInitStateEntity.Status.FAILED);
                        entity.setMessage(message != null ? message : "consumer failed");
                        entity.setUpdatedAt(Instant.now());
                        lpInitStateRepository.save(entity);
                    });
        } catch (Exception e) {
            log.debug("[lp-init-consumer] mark pending FAILED slot={} marketId={}: {}",
                    slot, cmd.getMarketId(), e.getMessage());
        }
    }

    private void upsertInitState(String lpUserId, LpInitCommand cmd,
                                  LpInitStateEntity.Status status, String message) {
        if (lpUserId == null || lpUserId.isBlank()) return;
        try {
            LpInitStateEntity entity = lpInitStateRepository
                    .findByLpUserIdAndEventIdAndMarketId(lpUserId, cmd.getEventId(), cmd.getMarketId())
                    .orElseGet(() -> {
                        LpInitStateEntity e = new LpInitStateEntity();
                        e.setLpUserId(lpUserId);
                        e.setEventId(cmd.getEventId());
                        e.setMarketId(cmd.getMarketId());
                        e.setCostRefId(cmd.getTraceId() != null ? cmd.getTraceId() : "v2");
                        e.setTotalCost(BigDecimal.ZERO);
                        e.setCreatedAt(Instant.now());
                        return e;
                    });
            entity.setStatus(status);
            entity.setMessage(message);
            entity.setUpdatedAt(Instant.now());
            lpInitStateRepository.save(entity);
        } catch (Exception e) {
            log.warn("[lp-init-consumer] upsert lp_init_state failed userId={} eventId={} marketId={}: {}",
                    lpUserId, cmd.getEventId(), cmd.getMarketId(), e.getMessage());
        }
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

    /**
     * 通知 lpbot 创建机器人用户 + 登录 + 缓存 token，lpbot 内部会写自己的
     * BotProductBinding 集合（带 spring-data _class），让 lpbot 自己的
     * /admin/bots?fixtureId=xxx 能 list 到这条 bot。
     *
     * <p>失败只 warn 不抛——portal 已经把 LP 资金/持仓初始化完成（callInitLpV2），
     * 下游 lpbot 注册即使失败也不该回滚整条消息。
     */
    private void notifyLpBotLogin(String eventId, String marketId, String selectionId) {
        if (selectionId == null || selectionId.isBlank()) return;
        try {
            String url = lpbotBaseUrl + "/admin/bots/login?"
                    + "eventId=" + enc(eventId)
                    + "&marketId=" + enc(marketId)
                    + "&selectionId=" + enc(selectionId);
            restTemplate.postForObject(url, null, Map.class);
            log.debug("[lp-init-consumer] lpbot login OK eventId={} marketId={} selectionId={}",
                    eventId, marketId, selectionId);
        } catch (Exception e) {
            log.warn("[lp-init-consumer] lpbot login FAIL eventId={} marketId={} selectionId={}: {}",
                    eventId, marketId, selectionId, e.getMessage());
        }
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
