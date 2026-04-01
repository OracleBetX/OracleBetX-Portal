package com.oraclebet.portal.api;

import com.oraclebet.portal.lp.dto.LpBatchInitRequest;
import com.oraclebet.portal.lp.dto.LpBatchInitResponse;
import com.oraclebet.portal.lp.dto.LpInitRequest;
import com.oraclebet.portal.lp.dto.LpInitResponse;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

    public LpInitController(LpInitService lpInitService,
                            LpBatchInitService batchInitService,
                            MongoTemplate mongoTemplate) {
        this.lpInitService = lpInitService;
        this.batchInitService = batchInitService;
        this.mongoTemplate = mongoTemplate;
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
     * POST /api/lp/init-batch — 批量初始化 LP
     * 遍历 event 下所有 market × outcome，创建用户 + 注资 + 持仓初始化
     */
    @PostMapping("/init-batch")
    public ResponseEntity<LpBatchInitResponse> initBatch(@RequestBody LpBatchInitRequest request) {
        log.info("[lp-batch-init] eventId={} markets={}", request.getEventId(),
                request.getItems() != null ? request.getItems().size() : 0);
        LpBatchInitResponse resp = batchInitService.batchInit(request);
        return ResponseEntity.ok(resp);
    }
}
