package com.oraclebet.portal.settlement.service;

import com.oraclebet.accountengine.api.AccountEngineSettlementDataApi;
import com.oraclebet.accountengine.api.dto.AccountEngineLedgerAccountStateDto;
import com.oraclebet.accountengine.api.dto.AccountEnginePositionLotDto;
import com.oraclebet.portal.settlement.entity.EventSettlementBatchEntity;
import com.oraclebet.portal.settlement.repo.EventSettlementBatchRepository;
import com.oraclebet.portal.settlement.repo.EventSettlementItemRepository;
import com.oraclebet.portal.settlement.utils.SettlementFactorCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSettlementService {

    private static final BigDecimal PAYOUT_100 = new BigDecimal("100");
    private static final int SCALE = 6;

    private final EventSettlementBatchRepository batchRepo;
    private final EventSettlementItemRepository itemRepo;

    private final AccountEngineSettlementDataApi settlementDataApi;

    // 幂等写账
    private final LedgerFacade ledgerFacade;

    /**
     * 模型1（LP资金池模型，保证两边平衡）
     *
     * 前提（必须满足，否则任何结算都会“多钱/少钱”）：
     * - 成交阶段：用户 BUY 成交时已经扣掉 cost（price*qty），并且这笔 cost 已进入 LP/资金池（或至少进入明确的池子账户）
     * - 结算阶段：只做 “LP 支付赢家 payoutFinal（=cost+profitScaled）”
     *
     * 本实现：
     * - 逐笔赢家 lot：LP reserve+commit 扣 payoutFinal；然后 user credit payoutFinal
     * - idemKey 按 lot 粒度保证幂等
     * - 汇总校验：LP总扣款 == 用户总入账（同一口径 & scale）
     */
    @Transactional(transactionManager = "exchangeTx")
    public void settleMarket(String eventId,
                             String marketId,
                             String winnerSelectionId,
                             String currency,
                             String accountType,
                             String settleBatchId,
                             String lpUserId) {

        // =========================
        // 0) 幂等：批次 DONE 直接跳过
        // =========================
        Optional<EventSettlementBatchEntity> existing = batchRepo.findById(settleBatchId);
        if (existing.isPresent() && existing.get().getStatus() == EventSettlementBatchEntity.Status.DONE) {
            log.info("【结算】批次已完成，跳过。batchId={} eventId={} marketId={}", settleBatchId, eventId, marketId);
            return;
        }

        // =========================
        // 1) 创建 / 加载批次
        // =========================
        EventSettlementBatchEntity batch = existing.orElseGet(() -> {
            EventSettlementBatchEntity b = new EventSettlementBatchEntity();
            b.setId(settleBatchId);
            b.setEventId(eventId);
            b.setMarketId(marketId);
            b.setWinnerSelectionId(winnerSelectionId);
            b.setPayout(PAYOUT_100);
            b.setStatus(EventSettlementBatchEntity.Status.NEW);
            return b;
        });
        batchRepo.save(batch);

        // =========================
        // 2) 锁定所有未结算仓位
        // =========================
        List<AccountEnginePositionLotDto> lots = settlementDataApi.lockUnsettledOpenLots(eventId, marketId);

        log.info("【结算】锁定未结算仓位完成，数量={}，胜方={}，eventId={} marketId={}",
                lots.size(), winnerSelectionId, eventId, marketId);

        if (lots.isEmpty()) {
            batch.setStatus(EventSettlementBatchEntity.Status.DONE);
            batch.setFactor(BigDecimal.ONE);
            batchRepo.save(batch);
            log.info("【结算】无仓位可结算，直接完成批次。batchId={}", settleBatchId);
            return;
        }

        // =========================
        // 3) 计算资金池（盈利池/亏损池）-> factor
        // =========================
        BigDecimal winnersProfit = BigDecimal.ZERO; // 赢家应得盈利（未缩放）
        BigDecimal losersLoss = BigDecimal.ZERO;    // 输家亏损（正数）

        Map<String, LotCalc> lotCalcMap = new LinkedHashMap<>();

        for (AccountEnginePositionLotDto lot : lots) {
            BigDecimal qty = nvl(lot.getOpenQty());
            if (qty.signum() <= 0) continue;

            BigDecimal price = nvl(lot.getEntryPrice());
            boolean isWinner = Objects.equals(lot.getSelectionId(), winnerSelectionId);

            BigDecimal pnlRaw;
            if (isWinner) {
                // WIN: 盈利 = (100 - price) * qty
                pnlRaw = PAYOUT_100.subtract(price).multiply(qty);
                winnersProfit = winnersProfit.add(pnlRaw);
            } else {
                // LOSE: 亏损 = price * qty（正数），pnlRaw 记负数
                pnlRaw = price.negate().multiply(qty);
                losersLoss = losersLoss.add(price.multiply(qty));
            }

            BigDecimal cost = price.multiply(qty);
            lotCalcMap.put(lot.getId(), new LotCalc(lot, qty, price, cost, pnlRaw, isWinner));
        }

        SettlementFactorCalculator.FactorResult fr =
                SettlementFactorCalculator.computeFactor(winnersProfit, losersLoss);

        BigDecimal factor = nvl(fr.getFactor()).setScale(SCALE, RoundingMode.DOWN);

        batch.setWinnersProfit(nvl(fr.getWinnersProfit()).setScale(SCALE, RoundingMode.DOWN));
        batch.setLosersLoss(nvl(fr.getLosersLoss()).setScale(SCALE, RoundingMode.DOWN));
        batch.setFactor(factor);
        batchRepo.save(batch);

        log.info("【结算-资金池】赢家盈利池(原始)={}，输家亏损池={}，缩放因子factor={}（currency={} type={}）",
                winnersProfit.toPlainString(),
                losersLoss.toPlainString(),
                factor.toPlainString(),
                currency, accountType);

        // =========================
        // 4) 逐仓位结算 & 打印输赢（只改仓位表，不改资金）
        // =========================
        Map<UserKey, UserAgg> userAggMap = new LinkedHashMap<>();

        BigDecimal totalCostAllLots = BigDecimal.ZERO;   // 成交成本总和（用于日志解释）
        BigDecimal totalWinnerPayout = BigDecimal.ZERO;  // 赢家最终应收总和（LP要支付出去）

        // 逐 lot 记录：赢家应收（用于模型1按 lot 做 LP->用户闭环）
        List<WinnerPay> winnerPays = new ArrayList<>();

        for (LotCalc lc : lotCalcMap.values()) {
            AccountEnginePositionLotDto lot = lc.lot;

            BigDecimal pnlFinal = lc.isWinner
                    ? lc.pnlRaw.multiply(factor).setScale(SCALE, RoundingMode.DOWN)
                    : lc.pnlRaw.setScale(SCALE, RoundingMode.DOWN); // 输家保持负数

            BigDecimal payoutFinal = lc.isWinner
                    ? lc.cost.add(pnlFinal).setScale(SCALE, RoundingMode.DOWN)
                    : BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN);

            totalCostAllLots = totalCostAllLots.add(lc.cost.setScale(SCALE, RoundingMode.DOWN));
            if (lc.isWinner) {
                totalWinnerPayout = totalWinnerPayout.add(payoutFinal);
                // 模型1：按 lot 支付（保证幂等 & 可追踪）
                winnerPays.add(new WinnerPay(lot.getId(), lot.getUserId(), payoutFinal));
            }

            long now = Instant.now().toEpochMilli();
            lot.setSettled(true);
            lot.setSettledAt(now);
            lot.setSettlementOutcome(lc.isWinner ? "WIN" : "LOSE");
            lot.setSettledPnl(pnlFinal);
            lot.setSettledPayout(payoutFinal);
            lot.setOpenQty(BigDecimal.ZERO);
            lot.setFrozenQty(BigDecimal.ZERO);
            lot.setUpdatedAt(now);

            // 仓位输赢日志
            if (lc.isWinner) {
                log.info("【结算-仓位】用户={} 选项={} 【赢】成本={} 赢利={} 最终应收={}",
                        lot.getUserId(),
                        lot.getSelectionId(),
                        lc.cost.setScale(SCALE, RoundingMode.DOWN).toPlainString(),
                        pnlFinal.toPlainString(),
                        payoutFinal.toPlainString());
            } else {
                log.info("【结算-仓位】用户={} 选项={} 【输】成本={} 亏损={}",
                        lot.getUserId(),
                        lot.getSelectionId(),
                        lc.cost.setScale(SCALE, RoundingMode.DOWN).toPlainString(),
                        pnlFinal.abs().toPlainString());
            }

            UserKey uk = new UserKey(lot.getUserId(), lot.getSelectionId());
            userAggMap.computeIfAbsent(uk, k -> new UserAgg(lot.getUserId(), lot.getSelectionId()))
                    .add(lc.qty, lc.cost, lc.pnlRaw, pnlFinal, payoutFinal);
        }

        settlementDataApi.saveLots(lots);

        // =========================
        // 5) 用户维度汇总日志（解释口径）
        // =========================
        log.info("【结算-资金走向说明】模型1：成交时用户已支付成本(cost)；结算时 LP 真实支付赢家 payout；两边才能平衡，不会凭空多钱。");
        log.info("【结算-汇总】totalCostAllLots(成交成本总和)={} totalWinnerPayout(赢家应收总和/LP需支付)={}",
                totalCostAllLots.setScale(SCALE, RoundingMode.DOWN).toPlainString(),
                totalWinnerPayout.setScale(SCALE, RoundingMode.DOWN).toPlainString());

        for (UserAgg agg : userAggMap.values()) {
            boolean isWinner = Objects.equals(agg.selectionId, winnerSelectionId);
            if (isWinner) {
                log.info("【结算-用户汇总】用户={} 选项={} 【赢家】成交已支付成本={}；最终应收={}（其中赢利={}）",
                        agg.userId,
                        agg.selectionId,
                        agg.costTotal.setScale(SCALE, RoundingMode.DOWN).toPlainString(),
                        agg.payoutFinalTotal.setScale(SCALE, RoundingMode.DOWN).toPlainString(),
                        agg.pnlFinalTotal.setScale(SCALE, RoundingMode.DOWN).toPlainString());
            } else {
                log.info("【结算-用户汇总】用户={} 选项={} 【输家】成交已支付成本={}；最终亏损={}",
                        agg.userId,
                        agg.selectionId,
                        agg.costTotal.setScale(SCALE, RoundingMode.DOWN).toPlainString(),
                        agg.pnlFinalTotal.abs().setScale(SCALE, RoundingMode.DOWN).toPlainString());
            }
        }

        // =========================
        // 6) 打印：LP 结算前真实账户
        // =========================
        AccountEngineLedgerAccountStateDto lpBefore = settlementDataApi.findLedgerAccount(lpUserId, currency, accountType);
        BigDecimal lpBeforeBal = nvl(lpBefore.getAvailable()).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal lpBeforeHeld = nvl(lpBefore.getFrozen()).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal lpBeforeAvail = lpBeforeBal.subtract(lpBeforeHeld).setScale(SCALE, RoundingMode.DOWN);

        log.info("【结算-LP】LP账户={} 结算前(真实) balance={} held={} avail={}",
                lpUserId, lpBeforeBal.toPlainString(), lpBeforeHeld.toPlainString(), lpBeforeAvail.toPlainString());

        // =========================
        // 7) ✅模型1核心：逐笔赢家 lot 执行 “LP reserve+commit -> user credit”
        //    目的：严格保证 sum(LP扣款) == sum(User入账)
        // =========================
        log.info("【结算-模型1执行】开始：逐笔赢家lot执行【LP冻结+扣款】并【用户CREDIT入账】（全部幂等）");
        BigDecimal sumLpDebit = BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN);
        BigDecimal sumUserCredit = BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN);

        for (WinnerPay wp : winnerPays) {
            String lotId = wp.lotId;
            String userId = wp.userId;
            BigDecimal amount = nvl(wp.amount).setScale(SCALE, RoundingMode.DOWN);
            if (amount.signum() <= 0) continue;

            if (wp.userId.equals(lpUserId)) {
                log.info(
                        "【结算-模型1-自赢跳过】lotId={} userId=LP({}) amount={}，" +
                                "该笔为 LP 自身赢利，不执行 LP 冻结/扣款/入账，仅用于结算统计",
                        wp.lotId,
                        lpUserId,
                        wp.amount.toPlainString()
                );
                continue;
            }

            // ---------- 幂等键（按 lot 粒度） ----------
            String lpReserveIdemKey = String.format("LEDGER:SETTLE:LP:RESERVE:%s", lotId);
            String lpCommitIdemKey  = String.format("LEDGER:SETTLE:LP:COMMIT:%s", lotId);
            String userCreditIdemKey = String.format("LEDGER:SETTLE:USER:CREDIT:%s", lotId);

            // ---------- refId：用于对账追踪 ----------
            // 你希望“把冻结ID扩展到 credit 用户里”，LedgerFacade 目前没有 attributes 入参，
            // 所以这里用 refId + reason 串起来（可用于审计查询）。
            String settleRefId = String.format("SETTLE:%s", lotId);

            String reason = String.format(
                    "event settlement payout (model1) eventId=%s marketId=%s winner=%s batchId=%s lotId=%s lpUserId=%s",
                    eventId, marketId, winnerSelectionId, settleBatchId, lotId, lpUserId
            );

            // 7.1 LP 冻结
            log.info("【结算-模型1】lotId={} 开始：LP冻结 reserve amount={} idemKey={}", lotId, amount.toPlainString(), lpReserveIdemKey);
            String reservationId = ledgerFacade.reserve(
                    lpUserId,
                    currency,
                    accountType,
                    amount,
                    lpReserveIdemKey,
                    reason
            );

            // 7.2 LP commit 扣款
            log.info("【结算-模型1】lotId={} LP冻结完成 reservationId={} -> commit amount={} idemKey={}",
                    lotId, reservationId, amount.toPlainString(), lpCommitIdemKey);

            ledgerFacade.commit(
                    lpUserId,
                    reservationId,
                    currency,
                    accountType,
                    amount,
                    lpCommitIdemKey,
                    reason
            );
            sumLpDebit = sumLpDebit.add(amount);

            // 7.3 用户 credit 入账（等额）
            // 注意：你的 LedgerFacade.credit 当前在你代码里是 7 参数版本：
            // credit(userId, currency, accountType, amount, refId, idemKey, reason)
            log.info("【结算-模型1】lotId={} 用户入账 CREDIT userId={} amount={} refId={} idemKey={}",
                    lotId, userId, amount.toPlainString(), settleRefId, userCreditIdemKey);

            ledgerFacade.credit(
                    userId,
                    currency,
                    accountType,
                    amount,
                    settleRefId,
                    userCreditIdemKey,
                    reason + " reservationId=" + reservationId
            );
            sumUserCredit = sumUserCredit.add(amount);

            // 7.4 打印用户真实账户（入账后）
            AccountEngineLedgerAccountStateDto userAfter = settlementDataApi.findLedgerAccount(userId, currency, accountType);
            BigDecimal ub = nvl(userAfter.getAvailable()).setScale(SCALE, RoundingMode.DOWN);
            BigDecimal uh = nvl(userAfter.getFrozen()).setScale(SCALE, RoundingMode.DOWN);
            BigDecimal ua = ub.subtract(uh).setScale(SCALE, RoundingMode.DOWN);

            log.info("【结算-模型1】lotId={} 用户={} 入账后(真实) balance={} held={} avail={}",
                    lotId, userId, ub.toPlainString(), uh.toPlainString(), ua.toPlainString());
        }

        // 7.5 打印 LP 结算后真实账户
        AccountEngineLedgerAccountStateDto lpAfter = settlementDataApi.findLedgerAccount(lpUserId, currency, accountType);
        BigDecimal lpAfterBal = nvl(lpAfter.getAvailable()).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal lpAfterHeld = nvl(lpAfter.getFrozen()).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal lpAfterAvail = lpAfterBal.subtract(lpAfterHeld).setScale(SCALE, RoundingMode.DOWN);

        log.info("【结算-LP】LP账户={} 结算后(真实) balance={} held={} avail={}",
                lpUserId, lpAfterBal.toPlainString(), lpAfterHeld.toPlainString(), lpAfterAvail.toPlainString());

        log.info("【结算-模型1执行】结束：sumLpDebit={} sumUserCredit={} diff(LP-USER)={}",
                sumLpDebit.toPlainString(),
                sumUserCredit.toPlainString(),
                sumLpDebit.subtract(sumUserCredit).setScale(SCALE, RoundingMode.DOWN).toPlainString());

        // 硬校验：必须平衡（同口径同scale）
        BigDecimal diff = sumLpDebit.subtract(sumUserCredit).setScale(SCALE, RoundingMode.DOWN);
        if (diff.compareTo(BigDecimal.ZERO.setScale(SCALE, RoundingMode.DOWN)) != 0) {
            log.error("【结算-模型1守恒失败】LP扣款与用户入账不一致！diff={} batchId={} eventId={} marketId={}",
                    diff.toPlainString(), settleBatchId, eventId, marketId);
            // 不要把批次标 DONE，避免错误批次被认为已完成
            batch.setStatus(EventSettlementBatchEntity.Status.FAILED);
            batchRepo.save(batch);
            return;
        }

        // =========================
        // 8) 标记批次完成
        // =========================
        batch.setStatus(EventSettlementBatchEntity.Status.DONE);
        batchRepo.save(batch);

        log.info("【结算完成】batchId={} eventId={} marketId={} winner={} factor={}",
                settleBatchId, eventId, marketId, winnerSelectionId, factor.toPlainString());
    }

    // =========================
    // 内部结构
    // =========================
    private record LotCalc(
            AccountEnginePositionLotDto lot,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal cost,
            BigDecimal pnlRaw,
            boolean isWinner
    ) {}

    private record UserKey(String userId, String selectionId) {}

    private static class UserAgg {
        final String userId;
        final String selectionId;

        BigDecimal qtyTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        BigDecimal pnlRawTotal = BigDecimal.ZERO;
        BigDecimal pnlFinalTotal = BigDecimal.ZERO;
        BigDecimal payoutFinalTotal = BigDecimal.ZERO;

        UserAgg(String userId, String selectionId) {
            this.userId = userId;
            this.selectionId = selectionId;
        }

        void add(BigDecimal qty,
                 BigDecimal cost,
                 BigDecimal pnlRaw,
                 BigDecimal pnlFinal,
                 BigDecimal payoutFinal) {

            qtyTotal = qtyTotal.add(nvl(qty));
            costTotal = costTotal.add(nvl(cost));
            pnlRawTotal = pnlRawTotal.add(nvl(pnlRaw));
            pnlFinalTotal = pnlFinalTotal.add(nvl(pnlFinal));
            payoutFinalTotal = payoutFinalTotal.add(nvl(payoutFinal));
        }
    }

    // 赢家每个 lot 的支付指令：LP->用户同额闭环
    private record WinnerPay(String lotId, String userId, BigDecimal amount) {}

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
