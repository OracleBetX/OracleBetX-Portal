package com.oraclebet.portal.lp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "lp_init_state",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_lp_init_state", columnNames = {"lp_user_id", "event_id", "market_id"})
        }
)
@Getter
@Setter
public class LpInitStateEntity {

    public enum Status { INITING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lp_user_id", length = 64, nullable = false)
    private String lpUserId;

    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "market_id", length = 64, nullable = false)
    private String marketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "cost_ref_id", length = 256, nullable = false)
    private String costRefId;

    @Column(name = "total_cost", precision = 38, scale = 18, nullable = false)
    private BigDecimal totalCost;

    @Column(name = "reservation_id", length = 128)
    private String reservationId;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
