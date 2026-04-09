package com.oraclebet.portal.settlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "order_anomaly_log", schema = "exchange",
        indexes = {
                @Index(name = "idx_oal_created_at", columnList = "created_at"),
                @Index(name = "idx_oal_order_id", columnList = "order_id, created_at")
        })
public class OrderAnomalyLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "user_id")
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private AnomalyAction action;

    @Column(name = "error_code", nullable = false, length = 64)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "engine_instance_id")
    private String engineInstanceId;

    @Column(name = "shard_key")
    private String shardKey;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "operation_id")
    private String operationId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** jsonb 扩展字段 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    private Map<String, Object> metaJson;
}
