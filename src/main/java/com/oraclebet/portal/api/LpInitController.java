package com.oraclebet.portal.api;

import com.oraclebet.portal.lp.dto.*;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.kafka.LpInitKafkaProducer;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
import com.oraclebet.portal.lp.service.LpBatchInitService;
import com.oraclebet.portal.lp.service.LpInitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LP 初始化接口。
 *
 * <pre>
 * POST /api/lp/init — LP 初始化（注资 + 冻结 + 扣款）+ 写 bot_product_binding
 * </pre>
 */
@RestController
@RequestMapping("/api/lp")
public class LpInitController {

    private static final Logger log = LoggerFactory.getLogger(LpInitController.class);
    private static final String BOT_BINDING_COLLECTION = "bot_product_binding";

    private final LpInitService lpInitService;
    private final LpBatchInitService batchInitService;
    private final MongoTemplate mongoTemplate;
    private final LpInitKafkaProducer lpInitKafkaProducer;
    private final LpInitStateRepository lpInitStateRepository;

    public LpInitController(LpInitService lpInitService,
                            LpBatchInitService batchInitService,
                            MongoTemplate mongoTemplate,
                            LpInitKafkaProducer lpInitKafkaProducer,
                            LpInitStateRepository lpInitStateRepository) {
        this.lpInitService = lpInitService;
        this.batchInitService = batchInitService;
        this.mongoTemplate = mongoTemplate;
        this.lpInitKafkaProducer = lpInitKafkaProducer;
        this.lpInitStateRepository = lpInitStateRepository;
    }

    @PostMapping("/init")
    public ResponseEntity<LpInitResponse> initLp(@RequestBody LpInitRequest request) {
        log.info("[lp-init] lpUserId={} eventId={} marketId={}",
                request.getLpUserId(), request.getEventId(), request.getMarketId());

        LpInitStateEntity state = lpInitService.initLp(request);

        // LP 初始化成功后，写 bot_product_binding（MongoDB x-bet 库）
        if ("DONE".equals(state.getStatus().name())) {
            upsertBotBinding(request);
        }

        LpInitResponse resp = new LpInitResponse(
                request.getLpUserId(),
                request.getEventId(),
                request.getMarketId(),
                state.getStatus().name(),
                state.getReservationId(),
                state.getMessage()
        );

        return ResponseEntity.ok(resp);
    }

    private void upsertBotBinding(LpInitRequest request) {
        try {
            String eventId = request.getEventId();
            String marketId = request.getMarketId() != null ? request.getMarketId() : "1";
            String accountId = request.getLpUserId();
            long now = System.currentTimeMillis();

            // home selection
            upsertOneBinding(eventId, marketId, request.getHomeSelectionId(), accountId, now);
            // away selection
            upsertOneBinding(eventId, marketId, request.getAwaySelectionId(), accountId, now);

            log.info("[lp-init] bot_product_binding upserted eventId={} accountId={}", eventId, accountId);
        } catch (Exception e) {
            log.warn("[lp-init] failed to upsert bot_product_binding: {}", e.getMessage());
        }
    }

    private void upsertOneBinding(String fixtureId, String marketId, String selectionId,
                                   String accountId, long now) {
        if (selectionId == null || selectionId.isBlank()) return;

        String sid = fixtureId + "_" + marketId + "_" + selectionId;
        Query q = Query.query(Criteria.where("sid").is(sid));
        Update u = new Update()
                .set("sid", sid)
                .set("fixtureId", fixtureId)
                .set("marketId", marketId)
                .set("selectionId", selectionId)
                .set("accountId", accountId)
                .set("status", "ACTIVE")
                .set("updatedAt", now)
                .setOnInsert("createdAt", now);
        mongoTemplate.upsert(q, u, BOT_BINDING_COLLECTION);
    }

    /**
     * POST /api/lp/fix-positions?eventId=xxx — 补充已 DONE 但缺持仓的 market
     */
    @PostMapping("/fix-positions")
    public ResponseEntity<Map<String, Object>> fixPositions(@RequestParam String eventId) {
        log.info("[fix-positions] eventId={}", eventId);
        int fixed = batchInitService.fixPositions(eventId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("fixedCount", fixed);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/lp/bindings?eventId=xxx — 查看绑定数据
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/bindings")
    public ResponseEntity<List<Map>> listBindings(@RequestParam String eventId) {
        Query q = Query.query(Criteria.where("fixtureId").is(eventId));
        List<Map> bindings = mongoTemplate.find(q, Map.class, BOT_BINDING_COLLECTION);
        return ResponseEntity.ok(bindings);
    }

    /**
     * POST /api/lp/fix-bindings?eventId=xxx — 单独修复绑定中缺失的 accountId
     */
    @PostMapping("/fix-bindings")
    public ResponseEntity<Map<String, Object>> fixBindings(@RequestParam String eventId) {
        log.info("[fix-bindings] eventId={}", eventId);
        int fixed = batchInitService.fixBindingsByEventId(eventId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("fixedCount", fixed);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/lp/init-batch — 批量初始化 LP（同步，会超时）
     */
    @PostMapping("/init-batch")
    public ResponseEntity<LpBatchInitResponse> initBatch(@RequestBody LpBatchInitRequest request) {
        log.info("[lp-batch-init] eventId={} markets={}", request.getEventId(),
                request.getItems() != null ? request.getItems().size() : 0);
        LpBatchInitResponse resp = batchInitService.batchInit(request);
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/lp/initV2 — 异步批量初始化 LP（Kafka）
     * <p>
     * 接收 LpBatchInitRequest，拆分成每个 market 一条 Kafka 消息，立即返回。
     * Consumer 后台处理：创建 bot 用户 → 注资 → 冻结 → 扣款 → 持仓初始化。
     * 通过 GET /api/lp/init-status?eventId=xxx 查询进度。
     */
    @PostMapping("/initV2")
    public ResponseEntity<Map<String, Object>> initV2(@RequestBody LpBatchInitRequest request) {
        String eventId = request.getEventId();
        List<LpBatchInitRequest.MarketInit> items = request.getItems();

        if (eventId == null || eventId.isBlank() || items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "message", "eventId and items required"));
        }

        log.info("[lp-initV2] eventId={} markets={}", eventId, items.size());

        String traceId = UUID.randomUUID().toString();
        BigDecimal initCash = request.getInitCash() != null ? request.getInitCash() : BigDecimal.ZERO;
        boolean cashSent = false;
        int sent = 0;

        for (LpBatchInitRequest.MarketInit market : items) {
            List<LpBatchInitRequest.OutcomeInit> outcomes = market.getOutcomes();
            if (outcomes == null || outcomes.size() != 2) {
                log.warn("[lp-initV2] skipped marketId={} (outcomes != 2)", market.getMarketId());
                continue;
            }

            LpBatchInitRequest.OutcomeInit home = outcomes.get(0);
            LpBatchInitRequest.OutcomeInit away = outcomes.get(1);

            LpInitCommand cmd = new LpInitCommand();
            cmd.setEventId(eventId);
            cmd.setMarketId(market.getMarketId());
            cmd.setHomeSelectionId(home.getSelectionId());
            cmd.setHomePrice(home.getPrice());
            cmd.setHomeQty(home.getQty());
            cmd.setAwaySelectionId(away.getSelectionId());
            cmd.setAwayPrice(away.getPrice());
            cmd.setAwayQty(away.getQty());
            cmd.setInitCash(!cashSent ? initCash : BigDecimal.ZERO);
            cmd.setTraceId(traceId);

            lpInitKafkaProducer.send(cmd);
            cashSent = true;
            sent++;
        }

        log.info("[lp-initV2] published {} messages, traceId={}", sent, traceId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("status", "PROCESSING");
        resp.put("eventId", eventId);
        resp.put("totalMarkets", sent);
        resp.put("traceId", traceId);
        return ResponseEntity.ok(resp);
    }

    /**
     * GET /api/lp/init-status?eventId=xxx — 查询 LP 初始化进度
     */
    @GetMapping("/init-status")
    public ResponseEntity<Map<String, Object>> initStatus(@RequestParam String eventId) {
        List<LpInitStateEntity> states = lpInitStateRepository.findByEventId(eventId);

        long done = states.stream().filter(s -> s.getStatus() == LpInitStateEntity.Status.DONE).count();
        long failed = states.stream().filter(s -> s.getStatus() == LpInitStateEntity.Status.FAILED).count();
        long initing = states.stream().filter(s -> s.getStatus() == LpInitStateEntity.Status.INITING).count();

        List<Map<String, Object>> details = states.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("marketId", s.getMarketId());
            m.put("lpUserId", s.getLpUserId());
            m.put("status", s.getStatus().name());
            m.put("message", s.getMessage());
            m.put("updatedAt", s.getUpdatedAt().toString());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("eventId", eventId);
        resp.put("total", states.size());
        resp.put("done", done);
        resp.put("failed", failed);
        resp.put("initing", initing);
        resp.put("details", details);
        return ResponseEntity.ok(resp);
    }
}
