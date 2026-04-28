package com.oraclebet.portal.lp.service;

import com.oraclebet.portal.lp.dto.LpInitCommand;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * 把"删 pending 占位 + upsert DONE"四步合到一个事务里，保证 consumer 处理完后
 * lp_init_state 不会出现"既无 pending 也无 DONE"的孤儿中间态。
 */
@Service
public class LpInitStateWriter {

    private static final Logger log = LoggerFactory.getLogger(LpInitStateWriter.class);

    private final LpInitStateRepository repository;

    public LpInitStateWriter(LpInitStateRepository repository) {
        this.repository = repository;
    }

    /**
     * 一个事务里完成：
     * 1) 删 home/away 两条 pending 占位行（如有）
     * 2) upsert 两条真实 lpUserId 的 DONE 行
     * <p>lpbot login 失败时把错误信息追加到 message 字段，仍保持 DONE（lpbot 注册失败不影响 portal/AE 已落地的资金/持仓）。
     */
    @Transactional
    public void commitDone(LpInitCommand cmd, String homeUserId, String awayUserId,
                           String homeLpbotErr, String awayLpbotErr) {
        deletePending(cmd, "h");
        deletePending(cmd, "a");
        upsert(homeUserId, cmd, LpInitStateEntity.Status.DONE, homeLpbotErr);
        upsert(awayUserId, cmd, LpInitStateEntity.Status.DONE, awayLpbotErr);
    }

    private void deletePending(LpInitCommand cmd, String slot) {
        if (cmd.getTraceId() == null) return;
        String pendingId = "pending-" + cmd.getTraceId() + "-" + cmd.getMarketId() + "-" + slot;
        repository.findByLpUserIdAndEventIdAndMarketId(pendingId, cmd.getEventId(), cmd.getMarketId())
                .ifPresent(repository::delete);
    }

    private void upsert(String lpUserId, LpInitCommand cmd,
                        LpInitStateEntity.Status status, String lpbotErr) {
        if (lpUserId == null || lpUserId.isBlank()) return;

        Optional<LpInitStateEntity> existing = repository
                .findByLpUserIdAndEventIdAndMarketId(lpUserId, cmd.getEventId(), cmd.getMarketId());
        boolean isNew = existing.isEmpty();
        LpInitStateEntity e = existing.orElseGet(() -> {
            LpInitStateEntity x = new LpInitStateEntity();
            x.setLpUserId(lpUserId);
            x.setEventId(cmd.getEventId());
            x.setMarketId(cmd.getMarketId());
            x.setCostRefId(cmd.getTraceId() != null ? cmd.getTraceId() : "v2");
            x.setTotalCost(BigDecimal.ZERO);
            x.setCreatedAt(Instant.now());
            return x;
        });
        e.setStatus(status);
        String tag = (status == LpInitStateEntity.Status.DONE)
                ? (isNew ? "OK (NEW)" : "OK (RE-INIT)")
                : status.name();
        if (lpbotErr != null && !lpbotErr.isBlank()) {
            tag = tag + " | LPBOT_FAIL: " + lpbotErr;
        }
        e.setMessage(tag);
        e.setUpdatedAt(Instant.now());
        repository.save(e);
    }
}
