package com.oraclebet.portal.lp.repo;

import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LpInitStateRepository extends JpaRepository<LpInitStateEntity, Long> {
    Optional<LpInitStateEntity> findByLpUserIdAndEventIdAndMarketId(String lpUserId, String eventId, String marketId);

    List<LpInitStateEntity> findByEventIdAndStatus(String eventId, LpInitStateEntity.Status status);

    List<LpInitStateEntity> findByEventId(String eventId);

    /** GC sweeper 用：扫超过阈值仍是 INITING 的孤儿占位 row。 */
    List<LpInitStateEntity> findByStatusAndUpdatedAtBefore(LpInitStateEntity.Status status, Instant cutoff);
}
