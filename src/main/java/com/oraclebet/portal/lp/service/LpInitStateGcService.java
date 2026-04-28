package com.oraclebet.portal.lp.service;

import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.repo.LpInitStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 定时清理孤儿 INITING 占位行。
 *
 * <p>背景：[LpInitController.initV2] 在发 Kafka 消息前先写 lp_init_state 占位 row（status=INITING，
 * lpUserId=pending-{traceId}-{marketId}-{slot}）让前端 GET /api/lp/init-status 能立即看到进度。
 * Consumer 处理完会 deletePendingState + upsertInitState(DONE)。
 *
 * <p>但如果 Kafka 消息丢失 / Portal 在 producer 写完 PG 后 consumer 处理前永久故障 / topic retention
 * 过期，占位 row 永远停在 INITING，前端 init-status 永远 initing&gt;0 不收敛，没有自愈机制。
 *
 * <p>本服务每 {@code lp.init.gc.interval-ms} 扫一次，将 updated_at 早于
 * {@code lp.init.gc.threshold-ms} 的 INITING 行标成 EXPIRED。
 */
@Service
public class LpInitStateGcService {

    private static final Logger log = LoggerFactory.getLogger(LpInitStateGcService.class);

    private final LpInitStateRepository repository;
    private final long thresholdMs;

    public LpInitStateGcService(LpInitStateRepository repository,
                                @Value("${lp.init.gc.threshold-ms:600000}") long thresholdMs) {
        this.repository = repository;
        this.thresholdMs = thresholdMs;
    }

    /** 默认 5 分钟跑一次；阈值默认 10 分钟。可通过 lp.init.gc.* 覆盖。 */
    @Scheduled(fixedDelayString = "${lp.init.gc.interval-ms:300000}", initialDelay = 60000)
    public void sweep() {
        Instant cutoff = Instant.now().minus(Duration.ofMillis(thresholdMs));
        List<LpInitStateEntity> orphans = repository.findByStatusAndUpdatedAtBefore(
                LpInitStateEntity.Status.INITING, cutoff);
        if (orphans.isEmpty()) return;

        for (LpInitStateEntity e : orphans) {
            e.setStatus(LpInitStateEntity.Status.EXPIRED);
            String prev = e.getMessage() == null ? "" : e.getMessage();
            e.setMessage("EXPIRED by gc; was: " + prev);
            e.setUpdatedAt(Instant.now());
        }
        repository.saveAll(orphans);
        log.warn("[lp-init-gc] expired {} orphan INITING rows (threshold={}ms)", orphans.size(), thresholdMs);
    }
}
