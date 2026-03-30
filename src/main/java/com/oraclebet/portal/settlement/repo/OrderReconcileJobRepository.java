package com.oraclebet.portal.settlement.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.oraclebet.portal.settlement.entity.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderReconcileJobRepository extends JpaRepository<OrderReconcileJobEntity, Long> {

    @Query(value = """
        select * from order_reconcile_job
        where job_status in ('NEW','RUNNING')
          and next_run_at <= now()
        order by next_run_at asc
        for update skip locked
        limit :limit
        """, nativeQuery = true)
    List<OrderReconcileJobEntity> lockDueJobs(@Param("limit") int limit);

    boolean existsByIdemKey(String idemKey);

    @Modifying
    @Query(value = """
            insert into order_reconcile_job (
                order_id, reservation_id, product_id, user_id,
                reason, job_status, next_run_at, engine_instance_id, shard_key, idem_key
            ) values (
                :orderId, :reservationId, :productId, :userId,
                :reason, :jobStatus, :nextRunAt, :engineInstanceId, :shardKey, :idemKey
            )
            on conflict (idem_key) do nothing
            """, nativeQuery = true)
    int insertIgnoreDuplicate(
            @Param("orderId") UUID orderId,
            @Param("reservationId") UUID reservationId,
            @Param("productId") String productId,
            @Param("userId") String userId,
            @Param("reason") String reason,
            @Param("jobStatus") String jobStatus,
            @Param("nextRunAt") OffsetDateTime nextRunAt,
            @Param("engineInstanceId") String engineInstanceId,
            @Param("shardKey") String shardKey,
            @Param("idemKey") String idemKey
    );
}
