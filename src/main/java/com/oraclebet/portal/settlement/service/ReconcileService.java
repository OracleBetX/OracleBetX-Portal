package com.oraclebet.portal.settlement.service;

import com.oraclebet.common.enums.OrderStatus;
import com.oraclebet.accountengine.api.AccountEngineSettleOrderApi;
import com.oraclebet.portal.settlement.entity.*;
import com.oraclebet.portal.settlement.repo.OrderAnomalyLogRepository;
import com.oraclebet.portal.settlement.repo.OrderReconcileCaseRepository;
import com.oraclebet.portal.settlement.repo.OrderReconcileJobRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);
    private final OrderAnomalyLogRepository anomalyLogRepo;
    private final OrderReconcileJobRepository jobRepo;
    private final OrderReconcileCaseRepository caseRepo;
    private final AccountEngineSettleOrderApi accountEngineSettleOrderApi;

    private final OrderStateRepository orderStateRepo;
    private final ReservationQueryService reservationQueryService;

    /** matchingengine/cancel 层在收到 NOT_FOUND 时调用 */
    @org.springframework.transaction.annotation.Transactional(transactionManager = "exchangeTx")
    public void onCancelNotFound(CancelNotFoundInput in) {
        UUID orderId = parseUuid(in.orderId());
        if (orderId == null) {
            log.warn("skip cancel-not-found reconcile: invalid orderId, raw={}", in.orderId());
            return;
        }

        //  查询到订单
        var optOrder = accountEngineSettleOrderApi.findByIdForUpdate(in.orderId());
        if (optOrder.isEmpty()) {
            log.error("Order id {} not found", in.orderId());
            return;
        }
        UUID reservationId = parseUuid(optOrder.get().getReservationId());

        // 1) 写异常流水（全量）
        OrderAnomalyLogEntity log = new OrderAnomalyLogEntity();
        log.setOrderId(orderId);
        log.setReservationId(reservationId);
        log.setProductId(optOrder.get().getEventId());
        log.setUserId(optOrder.get().getUserId());
        log.setAction(AnomalyAction.CANCEL);
        log.setErrorCode("ORDER_NOT_FOUND");
        log.setErrorMessage(in.errorMessage());
        log.setEngineInstanceId(in.engineInstanceId());
        log.setShardKey(in.shardKey());
        log.setTraceId(in.traceId());
        log.setOperationId(in.operationId());
        anomalyLogRepo.save(log);

        // 2) 入自动收敛任务队列（幂等）
        String idemKey = "RECON:" + in.orderId() + ":CANCEL_NOT_FOUND";
        jobRepo.insertIgnoreDuplicate(
                orderId,
                reservationId,
                optOrder.get().getEventId(),
                optOrder.get().getUserId(),
                "CANCEL_NOT_FOUND",
                JobStatus.NEW.name(),
                OffsetDateTime.now(),
                in.engineInstanceId(),
                in.shardKey(),
                idemKey
        );
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 处理单个 job：按 RSV 状态收敛订单终态
     * 规则：
     * - RSV=RELEASED => 订单应该 CANCELLED/CLOSED
     * - RSV=COMMITTED => 订单应该 FILLED/CLOSED
     * - RSV=OPEN => 查不到事实就重试（这里先重试；你也可以额外查 Trade/Cancel 事实事件）
     */
    @org.springframework.transaction.annotation.Transactional(transactionManager = "exchangeTx")
    public void processJob(OrderReconcileJobEntity job) {
        job.setJobStatus(JobStatus.RUNNING);
        jobRepo.save(job);

        var orderOpt = orderStateRepo.findByOrderId(job.getOrderId());
        if (orderOpt.isEmpty()) {
            markDone(job, "ORDER_ROW_MISSING", "Order row not found in DB, treat as DONE");
            return;
        }

        var order = orderOpt.get();

        if (!OrderStatus.OPEN.name().equals(order.orderState())) {
            markDone(job, "NOT_OPEN", "Order not OPEN, skip cancel reconcile");
            return;
        }

        try {
            accountEngineSettleOrderApi.cancel(order.orderId().toString());

            //  后置校验：避免 cancel 异步/未生效 你就 DONE
            var afterOpt = orderStateRepo.findByOrderId(job.getOrderId());
            if (afterOpt.isEmpty()) {
                markDone(job, "ORDER_ROW_MISSING_AFTER", "Order row missing after cancel, treat as DONE");
                return;
            }

            var after = afterOpt.get();
            if (!OrderStatus.OPEN.name().equals(after.orderState())) {
                // cancel 已推进投影（变成 CANCELLED/CLOSED 等）
                markDone(job, null, null);
            } else {
                // 仍 OPEN：说明 cancel 没收敛（可能是异步链路/事件未到/撮合缺单）
                retryLater(job, "CANCEL_NOT_EFFECTIVE", "cancel invoked but order still OPEN");
            }

        } catch (Exception ex) {
            retryLater(job, "RECONCILE_EXCEPTION", ex.getMessage());
        }
    }

    private void writeReconcileLog(OrderReconcileJobEntity job, String code, String msg) {
        OrderAnomalyLogEntity log = new OrderAnomalyLogEntity();
        log.setOrderId(job.getOrderId());
        log.setReservationId(job.getReservationId());
        log.setProductId(job.getProductId());
        log.setUserId(job.getUserId());
        log.setAction(AnomalyAction.RECONCILE);
        log.setErrorCode(code);
        log.setErrorMessage(msg);
        log.setEngineInstanceId(job.getEngineInstanceId());
        log.setShardKey(job.getShardKey());
        anomalyLogRepo.save(log);
    }

    private void markDone(OrderReconcileJobEntity job, String lastErrorCode, String lastErrorMessage) {
        job.setJobStatus(JobStatus.DONE);
        job.setLastErrorCode(lastErrorCode);
        job.setLastErrorMessage(lastErrorMessage);
        job.setNextRunAt(OffsetDateTime.now());
        jobRepo.save(job);
    }

    /** 指数退避：1s,5s,30s,2m,10m...（你可按需调整） */
    private void retryLater(OrderReconcileJobEntity job, String code, String msg) {
        int attempt = job.getAttempt() + 1;
        job.setAttempt(attempt);
        job.setLastErrorCode(code);
        job.setLastErrorMessage(msg);

        if (attempt >= job.getMaxAttempt()) {
            job.setJobStatus(JobStatus.DEAD);
            job.setNextRunAt(OffsetDateTime.now());
            jobRepo.save(job);
//            escalateToCase(job, code, msg); // 暂时不走客服处理
            return;
        }

        long delaySeconds = backoffSeconds(attempt);
        job.setJobStatus(JobStatus.RUNNING);
        job.setNextRunAt(OffsetDateTime.now().plusSeconds(delaySeconds));
        jobRepo.save(job);
        writeReconcileLog(job, code, msg + ", attempt=" + attempt + ", next_in_s=" + delaySeconds);
    }

    private long backoffSeconds(int attempt) {
        // 1, 5, 30, 120, 600, 600, ...
        return switch (attempt) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 30;
            case 4 -> 120;
            default -> 600;
        };
    }

    /** 升级到客服池（只在自动重试失败后） */
    private void escalateToCase(OrderReconcileJobEntity job, String code, String msg) {
        caseRepo.findOpenByOrderId(job.getOrderId()).ifPresentOrElse(existing -> {
            existing.setLastErrorCode(code);
            existing.setLastErrorAt(OffsetDateTime.now());
            existing.setNotes(msg);
            caseRepo.save(existing);
        }, () -> {
            // 拿订单状态用于展示（客服看）
            var order = orderStateRepo.findByOrderId(job.getOrderId()).orElse(null);

            OrderReconcileCaseEntity c = new OrderReconcileCaseEntity();
            c.setOrderId(job.getOrderId());
            c.setReservationId(job.getReservationId());
            c.setProductId(job.getProductId());
            c.setUserId(job.getUserId());
            c.setOrderState(order != null ? order.orderState() : "UNKNOWN");
            c.setRsvState("UNKNOWN");
            c.setLastErrorCode(code);
            c.setLastErrorAt(OffsetDateTime.now());
            c.setFirstSeenAt(OffsetDateTime.now());
            c.setCaseStatus(CaseStatus.OPEN);

            // 系统建议：如果 RSV 已 RELEASED/COMMITTED 可以建议直接落终态
            c.setSuggestedResolution("RETRY_RECONCILE");
            c.setNotes(msg);

            caseRepo.save(c);
        });

        OrderAnomalyLogEntity log = new OrderAnomalyLogEntity();
        log.setOrderId(job.getOrderId());
        log.setReservationId(job.getReservationId());
        log.setProductId(job.getProductId());
        log.setUserId(job.getUserId());
        log.setAction(AnomalyAction.RECONCILE);
        log.setErrorCode("ESCALATE_TO_CASE");
        log.setErrorMessage("Job DEAD, escalated to case. last=" + code);
        anomalyLogRepo.save(log);
    }
}
