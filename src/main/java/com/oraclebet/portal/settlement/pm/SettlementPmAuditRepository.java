package com.oraclebet.portal.settlement.pm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Repository
public class SettlementPmAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementPmAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AuditRecord record) {
        try {
            jdbcTemplate.update("""
                            insert into settlement_pm_audit (
                              id, settle_batch_id, event_id, market_id,
                              caller_selection_id, pm_selection_id, pm_confidence,
                              mode, decision, mismatch, reason, error_message,
                              actor, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(),
                    record.settleBatchId(),
                    record.eventId(),
                    record.marketId(),
                    record.callerSelectionId(),
                    record.pmSelectionId(),
                    record.pmConfidence(),
                    record.mode(),
                    record.decision(),
                    record.mismatch(),
                    record.reason(),
                    record.errorMessage(),
                    record.actor(),
                    Instant.now());
        } catch (DataAccessException e) {
            log.error("[PM-SETTLE] failed to write settlement_pm_audit eventId={} marketId={} batchId={} decision={}",
                    record.eventId(), record.marketId(), record.settleBatchId(), record.decision(), e);
        }
    }

    public record AuditRecord(String settleBatchId,
                              String eventId,
                              String marketId,
                              String callerSelectionId,
                              String pmSelectionId,
                              String pmConfidence,
                              String mode,
                              String decision,
                              boolean mismatch,
                              String reason,
                              String errorMessage,
                              String actor) {
    }
}
