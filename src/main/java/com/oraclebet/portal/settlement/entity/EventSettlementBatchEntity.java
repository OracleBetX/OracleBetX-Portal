package com.oraclebet.portal.settlement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "event_settlement_batch", schema = "exchange")
@Getter @Setter
public class EventSettlementBatchEntity {

    public enum Status { NEW, DONE, FAILED }

    @Id
    @Column(length = 64)
    private String id; // settleBatchId

    @Column(name="event_id", nullable=false, length=64)
    private String eventId;

    @Column(name="market_id", nullable=false, length=64)
    private String marketId;

    @Column(name="winner_selection_id", nullable=false, length=64)
    private String winnerSelectionId;

    @Column(name="payout", precision=36, scale=18, nullable=false)
    private BigDecimal payout = new BigDecimal("100");

    @Column(name="factor", precision=36, scale=18, nullable=false)
    private BigDecimal factor = BigDecimal.ONE;

    @Column(name="winners_profit", precision=36, scale=18, nullable=false)
    private BigDecimal winnersProfit = BigDecimal.ZERO;

    @Column(name="losers_loss", precision=36, scale=18, nullable=false)
    private BigDecimal losersLoss = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name="status", length=16, nullable=false)
    private Status status = Status.NEW;

    @Column(name="created_at", nullable=false)
    private long createdAt;

    @Column(name="updated_at", nullable=false)
    private long updatedAt;

    @PrePersist
    void prePersist() {
        long now = Instant.now().toEpochMilli();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now().toEpochMilli();
    }
}