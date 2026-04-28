package com.oraclebet.portal.lp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.common.config.kafka.KafkaProperties;
import com.oraclebet.common.config.kafka.TradingKafkaConsumerThread;
import com.oraclebet.common.config.kafka.TradingKafkaRecordDecoder;
import com.oraclebet.common.config.kafka.TradingKafkaTopics;
import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import com.oraclebet.portal.lp.LpbotMongoSchema;
import com.oraclebet.portal.lp.dto.LpInitCommand;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
import com.oraclebet.portal.lp.service.LpInitStateWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** 远端 IO 专用 RestTemplate — 自带 30s 超时，防止远端服务挂死时单条任务无限阻塞。 */
    private final RestTemplate restTemplate;
    private final MongoTemplate mongoTemplate;
    private final LpInitStateRepository lpInitStateRepository;
    private final LpInitStateWriter stateWriter;
    private final String lpbotBaseUrl;
    /** 并行 IO 线程池：整批 records 并发处理（每条消息一个 worker）。 */
    private final ExecutorService ioExecutor;
    /**
     * 子任务专用线程池：home/away 两端 callInitLpV2 / lpbot login 跑在这里。
     * <p>**必须与 ioExecutor 物理隔离**，否则同池里父任务（processOne）等子任务（callInitLpV2）会
     * 在 batch 大于池容量时彻底死锁——8 个父 worker 占满，子任务永远拿不到线程。
     */
    private final ExecutorService childExecutor;
    /** 当前 poll 批次缓冲：handleDecodedRecord 入队，afterPollBatch 一次性并发处理。 */
    private final Queue<LpInitCommand> currentBatch = new ConcurrentLinkedQueue<>();

    public LpInitKafkaConsumer(KafkaConsumer<String, byte[]> consumer,
                               KafkaProperties properties,
                               TradingKafkaTopics topics,
                               TradingKafkaRecordDecoder<LpInitCommand> decoder,
                               GatewayAddressProvider gatewayAddressProvider,
                               RestTemplate ignoredNodeRpcRestTemplate,
                               MongoTemplate mongoTemplate,
                               LpInitStateRepository lpInitStateRepository,
                               LpInitStateWriter stateWriter,
                               String lpbotBaseUrl) {
        super(consumer, log, properties, topics, decoder);
        this.gatewayAddressProvider = gatewayAddressProvider;
        // 不复用 libs 注入的 nodeRpcRestTemplate（无超时），自己包一个带 30s 读/连超时的，
        // 防止远端 AE / lpbot 挂死时 child 池被无限占用拖垮整个 consumer。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
        this.mongoTemplate = mongoTemplate;
        this.lpInitStateRepository = lpInitStateRepository;
        this.stateWriter = stateWriter;
        this.lpbotBaseUrl = (lpbotBaseUrl == null || lpbotBaseUrl.isBlank())
                ? "http://localhost:10020"
                : lpbotBaseUrl.replaceAll("/+$", "");
        this.ioExecutor = Executors.newFixedThreadPool(8, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(0);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "lp-init-io-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        this.childExecutor = Executors.newFixedThreadPool(32, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(0);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "lp-init-child-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Override
    protected void subscribeTopics() {
        consumer.subscribe(List.of(topics().lpInit()));
        log.info("[lp-init-consumer] subscribed to topic={}", topics().lpInit());
    }

    /**
     * 父类 for-each-record 串行调用本方法。这里不立即处理，先入队，等 afterPollBatch 一次性并发。
     * 这样一批 max-poll-records=10 的消息能并发处理（每 marketId 一个 future），处理时间从
     * "10 markets × 串行单条耗时" 缩到 "max(10 个并行 future)"。
     */
    @Override
    protected void handleDecodedRecord(ConsumerRecord<String, byte[]> rawRecord, LpInitCommand cmd) {
        currentBatch.add(cmd);
    }

    /**
     * 一批 records 全部入队后由父类调用：用 ioExecutor 并发处理，所有 future 完成才返回。
     * 任一 record 抛异常都会 propagate 出去，父类整批不 commit offset，下次 poll 重投本批。
     * 业务侧已经强幂等（AE init-lp-v2 + lpbot login + lp_init_state upsert），重投安全。
     */
    @Override
    protected void afterPollBatch() throws Exception {
        List<LpInitCommand> batch = new ArrayList<>();
        LpInitCommand c;
        while ((c = currentBatch.poll()) != null) batch.add(c);
        if (batch.isEmpty()) return;

        log.info("[lp-init-consumer] dispatching batch size={} in parallel", batch.size());
        long t0 = System.currentTimeMillis();
        List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());
        for (LpInitCommand cmd : batch) {
            futures.add(CompletableFuture.runAsync(() -> processOne(cmd), ioExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("[lp-init-consumer] batch done size={} elapsed={}ms",
                    batch.size(), System.currentTimeMillis() - t0);
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            log.error("[lp-init-consumer] batch failed size={} elapsed={}ms cause={}",
                    batch.size(), System.currentTimeMillis() - t0, cause.getMessage(), cause);
            throw (cause instanceof Exception) ? (Exception) cause : new Exception(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private void processOne(LpInitCommand cmd) {
        log.info("[lp-init-consumer] processing eventId={} marketId={}", cmd.getEventId(), cmd.getMarketId());

        String homeUserId = null;
        String awayUserId = null;
        try {
            // 1+2. Home/Away 并行调 AE init-lp-v2（每条 ~2-5s, 串行 4-10s, 并行 ~2-5s）
            // 子任务必须用 childExecutor，与父 ioExecutor 物理隔离防止池满死锁
            CompletableFuture<Map<String, String>> homeFut = CompletableFuture.supplyAsync(() ->
                    callInitLpV2(cmd.getEventId(), cmd.getMarketId(),
                            cmd.getHomeSelectionId(), cmd.getHomeQty(), cmd.getHomePrice()), childExecutor);
            CompletableFuture<Map<String, String>> awayFut = CompletableFuture.supplyAsync(() ->
                    callInitLpV2(cmd.getEventId(), cmd.getMarketId(),
                            cmd.getAwaySelectionId(), cmd.getAwayQty(), cmd.getAwayPrice()), childExecutor);
            Map<String, String> homeResult = homeFut.join();
            Map<String, String> awayResult = awayFut.join();
            homeUserId = homeResult.get("userId");
            awayUserId = awayResult.get("userId");
            log.info("[lp-init-consumer] home done userId={} selectionId={}", homeUserId, cmd.getHomeSelectionId());
            log.info("[lp-init-consumer] away done userId={} selectionId={}", awayUserId, cmd.getAwaySelectionId());

            // 3. 写 bot_product_binding（portal 自己一份，与 lpbot 端解耦）
            upsertBotBinding(cmd.getEventId(), cmd.getMarketId(), cmd.getHomeSelectionId(), homeUserId);
            upsertBotBinding(cmd.getEventId(), cmd.getMarketId(), cmd.getAwaySelectionId(), awayUserId);

            // 3b. 通知 lpbot 同步注册两个 selection 对应的 bot — 同样并行（用 childExecutor 隔离）。
            // 把 portal 收到的 home/away initPrice 也透传过去，让 lpbot 立即用这个价做第一波单。
            // 失败不抛异常（不让 lpbot 临时挂导致整批 init 重投死循环），改为返回失败原因
            // 写进 lp_init_state.message，让 GET /init-status 的 details 能 grep 出来人工修复。
            final String homePriceStr = cmd.getHomePrice() == null ? null : cmd.getHomePrice().toPlainString();
            final String awayPriceStr = cmd.getAwayPrice() == null ? null : cmd.getAwayPrice().toPlainString();
            CompletableFuture<String> homeNotify = CompletableFuture.supplyAsync(() ->
                    notifyLpBotLogin(cmd.getEventId(), cmd.getMarketId(), cmd.getHomeSelectionId(), homePriceStr), childExecutor);
            CompletableFuture<String> awayNotify = CompletableFuture.supplyAsync(() ->
                    notifyLpBotLogin(cmd.getEventId(), cmd.getMarketId(), cmd.getAwaySelectionId(), awayPriceStr), childExecutor);
            String homeLpbotErr = homeNotify.join();
            String awayLpbotErr = awayNotify.join();

            // 4. 写 lp_init_state (PostgreSQL exchange schema)，让 GET /api/lp/init-status 看得到进度
            //    Producer 已经预写两条 INITING 占位（pending-{traceId}-{marketId}-{h|a}），
            //    consumer 完成后把占位删掉，再写两条真实 lpUserId 的 DONE 记录（同事务）。
            //    AE init-lp-v2 是 idempotent，重跑时返回相同 lpUserId → 区分 NEW 与 RE-INIT
            //    写到 message 字段里，让 GET /init-status 能聚合"本次新建 vs 复用"两个数。
            //    若 lpbot login 失败，message 后追加 LPBOT_FAIL 标记（仍保持 DONE 状态）。
            stateWriter.commitDone(cmd, homeUserId, awayUserId, homeLpbotErr, awayLpbotErr);

            log.info("[lp-init-consumer] done eventId={} marketId={}", cmd.getEventId(), cmd.getMarketId());
        } catch (Exception e) {
            // 失败时把两条 pending 占位标记为 FAILED（保留可见进度）
            try {
                markPendingFailed(cmd, "h", e.getMessage());
                markPendingFailed(cmd, "a", e.getMessage());
            } catch (Exception ignore) {
                // best-effort
            }
            throw new RuntimeException(e);
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
     * 通知 lpbot 创建机器人用户 + 登录 + 缓存 token。
     *
     * <p>返回 null 代表成功；返回非 null 字符串是错误原因（写进 lp_init_state.message 让人工排查）。
     * 失败不抛异常 — portal 已经把 LP 资金/持仓初始化完成，整条消息不该因 lpbot 临时挂掉
     * 进入 Kafka 重投死循环（实测重投会让 init 流程整体卡死）。
     */
    private String notifyLpBotLogin(String eventId, String marketId, String selectionId, String initPrice) {
        if (selectionId == null || selectionId.isBlank()) return null;
        try {
            StringBuilder url = new StringBuilder(lpbotBaseUrl)
                    .append("/admin/bots/login?")
                    .append("eventId=").append(enc(eventId))
                    .append("&marketId=").append(enc(marketId))
                    .append("&selectionId=").append(enc(selectionId));
            // 把 portal 初始化时填的价格透传给 lpbot — lpbot 收到后立即用此价做首波 quote
            if (initPrice != null && !initPrice.isBlank()) {
                url.append("&initPrice=").append(enc(initPrice));
            }
            restTemplate.postForObject(url.toString(), null, Map.class);
            log.debug("[lp-init-consumer] lpbot login OK eventId={} marketId={} selectionId={}",
                    eventId, marketId, selectionId);
            return null;
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("[lp-init-consumer] lpbot login FAIL eventId={} marketId={} selectionId={}: {}",
                    eventId, marketId, selectionId, msg);
            return msg;
        }
    }

    private void upsertBotBinding(String eventId, String marketId,
                                   String selectionId, String accountId) {
        if (selectionId == null || selectionId.isBlank()) return;
        if (accountId == null || accountId.isBlank()) {
            log.warn("[lp-init-consumer] skip upsertBotBinding accountId is null fixture={} market={} selection={}",
                    eventId, marketId, selectionId);
            return;
        }

        try {
            // lpbot 端用 (fixtureId, marketId, selectionId) 三键查 BotProductBinding，
            // 同时反序列化按 _class 字段决定实体类型；写文档时带 lpbot 期望的 _class
            // 让 lpbot 的 BotProductBindingRepository.findByFixtureIdAndMarketIdAndSelectionId 能识别。
            long now = System.currentTimeMillis();
            Query q = Query.query(new Criteria().andOperator(
                    Criteria.where("fixtureId").is(eventId),
                    Criteria.where("marketId").is(marketId),
                    Criteria.where("selectionId").is(selectionId)
            ));
            Update u = new Update()
                    .set("fixtureId", eventId)
                    .set("marketId", marketId)
                    .set("selectionId", selectionId)
                    .set("accountId", accountId)
                    .set("status", "ACTIVE")
                    .set("updatedAt", now)
                    .set("_class", LpbotMongoSchema.BOT_PRODUCT_BINDING_CLASS)
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
