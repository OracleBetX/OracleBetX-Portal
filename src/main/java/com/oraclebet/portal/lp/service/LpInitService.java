package com.oraclebet.portal.lp.service;

import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import com.oraclebet.portal.lp.dto.LpInitRequest;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
import com.oraclebet.portal.settlement.service.LedgerFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * LP 初始化服务。
 *
 * <p>流程：可选注资(CREDIT) → 冻结成本(RESERVE) → 扣成本(COMMIT) → 初始化持仓(PG+Redis) → 标记 DONE
 * <p>通过 LedgerFacade 调网关转发到 AccountEngine 完成 Ledger 操作。
 * <p>通过网关调 AccountEngine 的 LpInventoryService 完成持仓初始化（PG写入+Redis同步）。
 * <p>幂等：同一 (lpUserId, eventId, marketId) 只执行一次。
 */
@Service
public class LpInitService {

    private static final Logger log = LoggerFactory.getLogger(LpInitService.class);
    private static final String CURRENCY = "USDT";
    private static final String ACCOUNT_TYPE = "CASH";

    private final LedgerFacade ledgerFacade;
    private final LpInitStateRepository lpInitStateRepository;
    private final GatewayAddressProvider gatewayAddressProvider;
    private final RestTemplate restTemplate;

    public LpInitService(LedgerFacade ledgerFacade,
                         LpInitStateRepository lpInitStateRepository,
                         GatewayAddressProvider gatewayAddressProvider,
                         RestTemplate nodeRpcRestTemplate) {
        this.ledgerFacade = ledgerFacade;
        this.lpInitStateRepository = lpInitStateRepository;
        this.gatewayAddressProvider = gatewayAddressProvider;
        this.restTemplate = nodeRpcRestTemplate;
    }

    public LpInitStateEntity initLp(LpInitRequest req) {
        validate(req);

        String lpUserId = req.getLpUserId();
        String eventId = req.getEventId();
        String marketId = req.getMarketId();
        boolean force = req.isForce();

        String costRefId = "LPINIT:COST:" + eventId + ":" + marketId;
        BigDecimal totalCost = req.getHomePrice().multiply(req.getHomeQty())
                .add(req.getAwayPrice().multiply(req.getAwayQty()));

        // 1) 幂等门闩；force=true 时即使 DONE/FAILED 也重置重做
        LpInitStateEntity gate = tryAcquireGate(lpUserId, eventId, marketId, costRefId, totalCost, force);
        if (!force && gate.getStatus() == LpInitStateEntity.Status.DONE) {
            log.info("LP_INIT_ALREADY_DONE lpUserId={} eventId={} marketId={}", lpUserId, eventId, marketId);
            return gate;
        }

        log.info("LP_INIT_START lpUserId={} eventId={} marketId={} totalCost={} force={}",
                lpUserId, eventId, marketId, totalCost, force);

        try {
            // 2) 可选注资 — idemKey 用事件+用户级（与 marketId 无关），N 个 market 共用一次注资
            if (req.getInitCash() != null && req.getInitCash().compareTo(BigDecimal.ZERO) > 0) {
                String cashRefId = "LPINIT:CASH:" + eventId + ":" + lpUserId;
                String cashIdemKey = "LEDGER:CREDIT:" + cashRefId;
                ledgerFacade.credit(lpUserId, CURRENCY, ACCOUNT_TYPE, req.getInitCash(),
                        cashRefId, cashIdemKey, "lp init cash");
                log.info("LP_INIT_CREDIT lpUserId={} amount={} cashRefId={}", lpUserId, req.getInitCash(), cashRefId);
            }

            // 3) 冻结总成本(RESERVE)
            String reservationId = ledgerFacade.reserve(lpUserId, CURRENCY, ACCOUNT_TYPE,
                    totalCost, costRefId, "lp init reserve cost");
            gate.setReservationId(reservationId);
            lpInitStateRepository.save(gate);
            log.info("LP_INIT_RESERVE lpUserId={} totalCost={} rsvId={}", lpUserId, totalCost, reservationId);

            // 4) 扣成本(COMMIT)
            String commitIdemKey = "LEDGER:COMMIT:" + costRefId;
            ledgerFacade.commit(lpUserId, reservationId, CURRENCY, ACCOUNT_TYPE, totalCost, commitIdemKey, "lp init commit cost");
            log.info("LP_INIT_COMMIT lpUserId={} totalCost={}", lpUserId, totalCost);

            // 5) 初始化持仓（PG + Redis，调 AccountEngine LpInventoryService）
            initInventory(lpUserId, eventId, marketId, req.getHomeSelectionId(), req.getHomePrice(), req.getHomeQty());
            initInventory(lpUserId, eventId, marketId, req.getAwaySelectionId(), req.getAwayPrice(), req.getAwayQty());
            log.info("LP_INIT_POSITION lpUserId={} eventId={} marketId={}", lpUserId, eventId, marketId);

            // 6) 标记 DONE
            gate.setStatus(LpInitStateEntity.Status.DONE);
            gate.setMessage("OK");
            lpInitStateRepository.save(gate);

            log.info("LP_INIT_DONE lpUserId={} eventId={} marketId={}", lpUserId, eventId, marketId);
            return gate;

        } catch (Exception e) {
            gate.setStatus(LpInitStateEntity.Status.FAILED);
            gate.setMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            lpInitStateRepository.save(gate);
            log.error("LP_INIT_FAILED lpUserId={} eventId={} marketId={} err={}", lpUserId, eventId, marketId, e.toString(), e);
            throw e;
        }
    }

    /**
     * 调网关转发到 AccountEngine /api/account/lp/init-inventory
     */
    public void initInventory(String userId, String eventId, String marketId,
                               String selectionId, BigDecimal price, BigDecimal qty) {
        String url = gatewayAddressProvider.getGatewayUrl() + "/api/account/lp/init-inventory?"
                + "userId=" + enc(userId)
                + "&eventId=" + enc(eventId)
                + "&marketId=" + enc(marketId)
                + "&selectionId=" + enc(selectionId)
                + "&qty=" + qty.toPlainString()
                + "&price=" + price.toPlainString();
        restTemplate.postForObject(url, null, Object.class);
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void validate(LpInitRequest req) {
        if (req.getLpUserId() == null || req.getLpUserId().isBlank()) throw new IllegalArgumentException("lpUserId required");
        if (req.getEventId() == null || req.getEventId().isBlank()) throw new IllegalArgumentException("eventId required");
        if (req.getMarketId() == null || req.getMarketId().isBlank()) throw new IllegalArgumentException("marketId required");
        if (req.getHomeSelectionId() == null) throw new IllegalArgumentException("homeSelectionId required");
        if (req.getAwaySelectionId() == null) throw new IllegalArgumentException("awaySelectionId required");
        if (req.getHomePrice() == null || req.getHomePrice().signum() <= 0) throw new IllegalArgumentException("homePrice > 0 required");
        if (req.getAwayPrice() == null || req.getAwayPrice().signum() <= 0) throw new IllegalArgumentException("awayPrice > 0 required");
        if (req.getHomeQty() == null || req.getHomeQty().signum() <= 0) throw new IllegalArgumentException("homeQty > 0 required");
        if (req.getAwayQty() == null || req.getAwayQty().signum() <= 0) throw new IllegalArgumentException("awayQty > 0 required");
    }

    private LpInitStateEntity tryAcquireGate(String lpUserId, String eventId, String marketId,
                                             String costRefId, BigDecimal totalCost, boolean force) {
        var existing = lpInitStateRepository.findByLpUserIdAndEventIdAndMarketId(lpUserId, eventId, marketId);
        if (existing.isPresent()) {
            LpInitStateEntity old = existing.get();
            if (!force) return old;
            // force=true：复用旧 row（保留 id），状态重置为 INITING，让流程重新跑一遍
            old.setStatus(LpInitStateEntity.Status.INITING);
            old.setCostRefId(costRefId);
            old.setTotalCost(totalCost);
            old.setReservationId(null);
            old.setMessage("RESET_BY_FORCE");
            return lpInitStateRepository.save(old);
        }

        LpInitStateEntity gate = new LpInitStateEntity();
        gate.setLpUserId(lpUserId);
        gate.setEventId(eventId);
        gate.setMarketId(marketId);
        gate.setStatus(LpInitStateEntity.Status.INITING);
        gate.setCostRefId(costRefId);
        gate.setTotalCost(totalCost);
        gate.setMessage("INITING");

        try {
            return lpInitStateRepository.save(gate);
        } catch (DataIntegrityViolationException dup) {
            return lpInitStateRepository.findByLpUserIdAndEventIdAndMarketId(lpUserId, eventId, marketId)
                    .orElseThrow(() -> dup);
        }
    }
}
