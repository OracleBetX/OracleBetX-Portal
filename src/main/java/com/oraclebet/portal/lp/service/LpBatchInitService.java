package com.oraclebet.portal.lp.service;

import com.oraclebet.accountengine.api.AccountEngineUserApi;
import com.oraclebet.portal.lp.dto.LpBatchInitRequest;
import com.oraclebet.portal.lp.dto.LpBatchInitResponse;
import com.oraclebet.portal.lp.dto.LpInitRequest;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class LpBatchInitService {

    private static final Logger log = LoggerFactory.getLogger(LpBatchInitService.class);

    private final LpInitService lpInitService;
    private final MongoTemplate mongoTemplate;
    private final AccountEngineUserApi userApi;

    public LpBatchInitService(LpInitService lpInitService,
                              MongoTemplate mongoTemplate,
                              AccountEngineUserApi userApi) {
        this.lpInitService = lpInitService;
        this.mongoTemplate = mongoTemplate;
        this.userApi = userApi;
    }

    public LpBatchInitResponse batchInit(LpBatchInitRequest req) {
        String eventId = req.getEventId();
        List<LpBatchInitRequest.MarketInit> items = req.getItems();
        BigDecimal initCash = req.getInitCash() != null ? req.getInitCash() : BigDecimal.ZERO;

        // 1. 收集所有 email，一次性批量查 userId
        Map<String, String> emailToUserId = batchLookupUserIds(eventId, items);
        log.info("[batch-init] eventId={} markets={} usersFound={}", eventId, items.size(), emailToUserId.size());

        // 2. 回填 bot_product_binding 中缺失的 accountId
        fixBindings(eventId, emailToUserId);

        List<LpBatchInitResponse.OutcomeResult> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        boolean cashCredited = false;

        for (LpBatchInitRequest.MarketInit market : items) {
            String marketId = market.getMarketId();
            List<LpBatchInitRequest.OutcomeInit> outcomes = market.getOutcomes();
            if (outcomes == null || outcomes.isEmpty()) continue;

            if (outcomes.size() == 2) {
                LpBatchInitRequest.OutcomeInit home = outcomes.get(0);
                LpBatchInitRequest.OutcomeInit away = outcomes.get(1);

                String homeEmail = botEmail(eventId, marketId, home.getSelectionId());
                String awayEmail = botEmail(eventId, marketId, away.getSelectionId());
                String homeUserId = emailToUserId.get(homeEmail);
                String awayUserId = emailToUserId.get(awayEmail);
                String lpUserId = homeUserId != null ? homeUserId : awayUserId;

                if (lpUserId == null || lpUserId.isBlank()) {
                    addResult(results, marketId, home.getSelectionId(), null, "SKIPPED", "Bot 用户未找到");
                    addResult(results, marketId, away.getSelectionId(), null, "SKIPPED", "Bot 用户未找到");
                    failed += 2;
                    continue;
                }

                try {
                    LpInitRequest initReq = new LpInitRequest();
                    initReq.setLpUserId(lpUserId);
                    initReq.setEventId(eventId);
                    initReq.setMarketId(marketId);
                    initReq.setHomeSelectionId(home.getSelectionId());
                    initReq.setHomePrice(home.getPrice());
                    initReq.setHomeQty(home.getQty());
                    initReq.setAwaySelectionId(away.getSelectionId());
                    initReq.setAwayPrice(away.getPrice());
                    initReq.setAwayQty(away.getQty());
                    initReq.setInitCash(!cashCredited ? initCash : BigDecimal.ZERO);

                    LpInitStateEntity state = lpInitService.initLp(initReq);
                    cashCredited = true;

                    String status = state.getStatus().name();
                    addResult(results, marketId, home.getSelectionId(), homeUserId, status, state.getMessage());
                    addResult(results, marketId, away.getSelectionId(), awayUserId, status, state.getMessage());
                    success += 2;
                    log.info("[batch-init] market={} status={}", marketId, status);
                } catch (Exception e) {
                    log.warn("[batch-init] failed market={} err={}", marketId, e.getMessage());
                    addResult(results, marketId, home.getSelectionId(), homeUserId, "FAILED", e.getMessage());
                    addResult(results, marketId, away.getSelectionId(), awayUserId, "FAILED", e.getMessage());
                    failed += 2;
                }
            } else {
                for (LpBatchInitRequest.OutcomeInit outcome : outcomes) {
                    addResult(results, marketId, outcome.getSelectionId(), null, "SKIPPED", "多 outcome 暂不支持");
                    failed++;
                }
            }
        }

        LpBatchInitResponse resp = new LpBatchInitResponse();
        resp.setOk(true);
        resp.setEventId(eventId);
        resp.setTotalMarkets(items.size());
        resp.setTotalOutcomes(results.size());
        resp.setSuccessCount(success);
        resp.setFailedCount(failed);
        resp.setResults(results);
        return resp;
    }

    /**
     * 一次性收集所有 outcome 的 email，批量查 AccountEngine 拿 userId
     */
    private Map<String, String> batchLookupUserIds(String eventId, List<LpBatchInitRequest.MarketInit> items) {
        List<String> emails = new ArrayList<>();
        for (LpBatchInitRequest.MarketInit market : items) {
            for (LpBatchInitRequest.OutcomeInit outcome : market.getOutcomes()) {
                emails.add(botEmail(eventId, market.getMarketId(), outcome.getSelectionId()));
            }
        }
        if (emails.isEmpty()) return Map.of();

        // 一次 RPC 调用批量查
        return userApi.findUserIdsByEmails(emails);
    }

    /**
     * 回填 bot_product_binding 中 accountId 为空的记录
     */
    @SuppressWarnings("unchecked")
    private void fixBindings(String eventId, Map<String, String> emailToUserId) {
        Query q = Query.query(Criteria.where("fixtureId").is(eventId).and("status").is("ACTIVE"));
        List<Map> bindings = mongoTemplate.find(q, Map.class, "bot_product_binding");
        int fixed = 0;
        for (Map b : bindings) {
            String accountId = String.valueOf(b.getOrDefault("accountId", ""));
            if (!accountId.isEmpty() && !"null".equals(accountId)) continue;

            String sid = String.valueOf(b.getOrDefault("sid", ""));
            String marketId = String.valueOf(b.getOrDefault("marketId", ""));
            String selectionId = String.valueOf(b.getOrDefault("selectionId", ""));
            String email = botEmail(eventId, marketId, selectionId);
            String userId = emailToUserId.get(email);

            if (userId != null) {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("sid").is(sid)),
                        Update.update("accountId", userId).set("updatedAt", System.currentTimeMillis()),
                        "bot_product_binding"
                );
                fixed++;
            }
        }
        if (fixed > 0) {
            log.info("[batch-init] fixed {} bindings accountId for eventId={}", fixed, eventId);
        }
    }

    private String botEmail(String eventId, String marketId, String outcomeId) {
        return safe(eventId) + "_" + safe(marketId) + "_" + safe(outcomeId) + "_bot@xbet.com";
    }

    private String safe(String s) {
        return s == null ? "null" : s.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void addResult(List<LpBatchInitResponse.OutcomeResult> results,
                           String marketId, String selectionId, String userId,
                           String status, String message) {
        LpBatchInitResponse.OutcomeResult r = new LpBatchInitResponse.OutcomeResult();
        r.setMarketId(marketId);
        r.setSelectionId(selectionId);
        r.setUserId(userId);
        r.setStatus(status);
        r.setMessage(message);
        results.add(r);
    }
}
