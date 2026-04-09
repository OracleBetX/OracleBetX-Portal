package com.oraclebet.portal.settlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "order_reconcile_job", schema = "exchange",
        uniqueConstraints = @UniqueConstraint(name = "uk_reconcile_idem", columnNames = "idem_key"),
        indexes = {
                @Index(name = "idx_orj_next_run", columnList = "job_status, next_run_at"),
                @Index(name = "idx_orj_order_id", columnList = "order_id")
        })
public class OrderReconcileJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status", nullable = false, length = 16)
    private JobStatus jobStatus = JobStatus.NEW;

    @Column(name = "attempt", nullable = false)
    private int attempt = 0;

    @Column(name = "max_attempt", nullable = false)
    private int maxAttempt = 10;

    @Column(name = "next_run_at", nullable = false)
    private OffsetDateTime nextRunAt = OffsetDateTime.now();

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "engine_instance_id")
    private String engineInstanceId;

    @Column(name = "shard_key")
    private String shardKey;

    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
