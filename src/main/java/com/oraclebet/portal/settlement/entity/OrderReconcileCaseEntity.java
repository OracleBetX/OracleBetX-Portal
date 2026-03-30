package com.oraclebet.portal.settlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "order_reconcile_case",
        indexes = {
                @Index(name = "idx_orc_status", columnList = "case_status, last_error_at")
        })
public class OrderReconcileCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long caseId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "order_state", nullable = false, length = 32)
    private String orderState;

    @Column(name = "rsv_state", length = 16)
    private String rsvState;

    @Column(name = "last_error_code", nullable = false, length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_at", nullable = false)
    private OffsetDateTime lastErrorAt = OffsetDateTime.now();

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt = OffsetDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", nullable = false, length = 16)
    private CaseStatus caseStatus = CaseStatus.OPEN;

    @Column(name = "suggested_resolution", length = 32)
    private String suggestedResolution;

    @Column(name = "assignee", length = 64)
    private String assignee;

    @Column(name = "notes")
    private String notes;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
