package com.oraclebet.portal.settlement.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "event_settlement_item",
        indexes = {
                @Index(name="idx_esi_batch_user", columnList="batch_id,user_id"),
                @Index(name="idx_esi_event_market", columnList="event_id,market_id")
        }
)
@Getter @Setter
public class EventSettlementItemEntity {

    public enum Outcome { WIN, LOSE }

    @Id
    @Column(length = 64)
    private String id;

    @Column(name="batch_id", nullable=false, length=64)
    private String batchId;

    @Column(name="user_id", nullable=false, length=64)
    private String userId;

    @Column(name="event_id", nullable=false, length=64)
    private String eventId;

    @Column(name="market_id", nullable=false, length=64)
    private String marketId;

    @Column(name="selection_id", nullable=false, length=64)
    private String selectionId;

    @Enumerated(EnumType.STRING)
    @Column(name="outcome", length=16, nullable=false)
    private Outcome outcome;

    @Column(name="qty_settled", precision=36, scale=18, nullable=false)
    private BigDecimal qtySettled = BigDecimal.ZERO;

    @Column(name="cost_amount", precision=36, scale=18, nullable=false)
    private BigDecimal costAmount = BigDecimal.ZERO;

    @Column(name="pnl_raw", precision=36, scale=18, nullable=false)
    private BigDecimal pnlRaw = BigDecimal.ZERO;

    @Column(name="pnl_final", precision=36, scale=18, nullable=false)
    private BigDecimal pnlFinal = BigDecimal.ZERO;

    @Column(name="payout_final", precision=36, scale=18, nullable=false)
    private BigDecimal payoutFinal = BigDecimal.ZERO;

    @Column(name="ledger_idem_key", length=256)
    private String ledgerIdemKey;

    @Column(name="ledger_applied", nullable=false)
    private boolean ledgerApplied = false;

    @Column(name="created_at", nullable=false)
    private long createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now().toEpochMilli();
    }
}