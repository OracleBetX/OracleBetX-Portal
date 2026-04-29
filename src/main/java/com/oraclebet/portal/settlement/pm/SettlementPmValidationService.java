package com.oraclebet.portal.settlement.pm;

import com.oraclebet.contracts.pm.PmWinningSelectionClient;
import com.oraclebet.contracts.pm.WinningSelectionDto;
import com.oraclebet.portal.settlement.dto.SettleMarketRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Slf4j
@Service
public class SettlementPmValidationService {

    private final SettlementPmValidationProperties props;
    private final PmWinningSelectionClient pmClient;
    private final SettlementPmAuditRepository auditRepository;
    private final MeterRegistry meterRegistry;

    public SettlementPmValidationService(SettlementPmValidationProperties props,
                                         PmWinningSelectionClient pmClient,
                                         SettlementPmAuditRepository auditRepository,
                                         MeterRegistry meterRegistry) {
        this.props = props;
        this.pmClient = pmClient;
        this.auditRepository = auditRepository;
        this.meterRegistry = meterRegistry;
    }

    public ValidationResult validate(SettleMarketRequest req, String actor) {
        SettlementPmValidationProperties.Mode mode = props.getMode();
        if (mode == SettlementPmValidationProperties.Mode.OFF) {
            return ValidationResult.allowed("SKIPPED", "pm validation disabled");
        }

        try {
            WinningSelectionDto pm = pmClient.getWinningSelection(req.getEventId(), req.getMarketId());
            if (pm == null) {
                return handleError(req, actor, mode, "PM_NULL", "PM winning-selection response is null", null);
            }

            String confidence = pm.getConfidence() == null ? "UNKNOWN" : pm.getConfidence().name();
            boolean mismatch = !Objects.equals(req.getWinnerSelectionId(), pm.getSelectionId());
            boolean finalMatch = pm.getConfidence() == WinningSelectionDto.Confidence.FINAL && !mismatch;
            String decision = decision(mode, pm.getConfidence(), mismatch);
            String reason = reason(pm, mismatch);
            boolean allowed = mode == SettlementPmValidationProperties.Mode.WARN || finalMatch;

            audit(req, actor, mode.name(), decision, mismatch, pm.getSelectionId(), confidence, reason, null);
            metric(mode.name(), decision, confidence, mismatch);

            if (!allowed) {
                log.warn("[PM-SETTLE] blocked settlement eventId={} marketId={} callerSelectionId={} pmSelectionId={} confidence={} reason={}",
                        req.getEventId(), req.getMarketId(), req.getWinnerSelectionId(), pm.getSelectionId(), confidence, reason);
                return ValidationResult.blocked(decision, reason, pm);
            }

            if (mismatch) {
                log.error("[PM-SETTLE] selection mismatch eventId={} marketId={} callerSelectionId={} pmSelectionId={} confidence={}",
                        req.getEventId(), req.getMarketId(), req.getWinnerSelectionId(), pm.getSelectionId(), confidence);
            } else {
                log.info("[PM-SETTLE] validation {} eventId={} marketId={} selectionId={} confidence={}",
                        decision, req.getEventId(), req.getMarketId(), req.getWinnerSelectionId(), confidence);
            }
            return ValidationResult.allowed(decision, reason, pm);
        } catch (Exception e) {
            return handleError(req, actor, mode, "PM_ERROR", "PM validation failed: " + e.getMessage(), e);
        }
    }

    private ValidationResult handleError(SettleMarketRequest req,
                                         String actor,
                                         SettlementPmValidationProperties.Mode mode,
                                         String decision,
                                         String reason,
                                         Exception e) {
        boolean allowed = mode == SettlementPmValidationProperties.Mode.WARN;
        audit(req, actor, mode.name(), decision, false, null, null, reason, e == null ? null : e.getMessage());
        metric(mode.name(), decision, "ERROR", false);
        if (allowed) {
            log.warn("[PM-SETTLE] validation unavailable but warn mode allows settlement eventId={} marketId={} reason={}",
                    req.getEventId(), req.getMarketId(), reason);
            return ValidationResult.allowed(decision, reason);
        }
        log.error("[PM-SETTLE] validation unavailable and enforce blocks settlement eventId={} marketId={} reason={}",
                req.getEventId(), req.getMarketId(), reason, e);
        return ValidationResult.blocked(decision, reason, null);
    }

    private void audit(SettleMarketRequest req,
                       String actor,
                       String mode,
                       String decision,
                       boolean mismatch,
                       String pmSelectionId,
                       String confidence,
                       String reason,
                       String errorMessage) {
        auditRepository.insert(new SettlementPmAuditRepository.AuditRecord(
                req.getSettleBatchId(),
                req.getEventId(),
                req.getMarketId(),
                req.getWinnerSelectionId(),
                pmSelectionId,
                confidence,
                mode,
                decision,
                mismatch,
                reason,
                errorMessage,
                actor));
    }

    private void metric(String mode, String decision, String confidence, boolean mismatch) {
        Counter.builder("settlement_pm_validation_total")
                .tag("mode", mode)
                .tag("decision", decision)
                .tag("confidence", confidence == null ? "UNKNOWN" : confidence)
                .tag("mismatch", Boolean.toString(mismatch))
                .register(meterRegistry)
                .increment();
        if (mismatch) {
            Counter.builder("settlement_pm_mismatch_total")
                    .register(meterRegistry)
                    .increment();
        }
    }

    private static String decision(SettlementPmValidationProperties.Mode mode,
                                   WinningSelectionDto.Confidence confidence,
                                   boolean mismatch) {
        if (mode == SettlementPmValidationProperties.Mode.WARN) {
            if (mismatch) return "WARN_MISMATCH";
            return "WARN_MATCH";
        }
        if (confidence != WinningSelectionDto.Confidence.FINAL) {
            return "BLOCK_NOT_FINAL";
        }
        if (mismatch) {
            return "BLOCK_MISMATCH";
        }
        return "PASS";
    }

    private static String reason(WinningSelectionDto pm, boolean mismatch) {
        if (mismatch) {
            return "caller selectionId does not match PM winning selection";
        }
        if (pm.getConfidence() != WinningSelectionDto.Confidence.FINAL) {
            return pm.getReason() == null ? "PM confidence is not FINAL" : pm.getReason();
        }
        return pm.getReason();
    }

    public record ValidationResult(boolean allowed,
                                   String decision,
                                   String reason,
                                   WinningSelectionDto pm) {
        static ValidationResult allowed(String decision, String reason) {
            return new ValidationResult(true, decision, reason, null);
        }

        static ValidationResult allowed(String decision, String reason, WinningSelectionDto pm) {
            return new ValidationResult(true, decision, reason, pm);
        }

        static ValidationResult blocked(String decision, String reason, WinningSelectionDto pm) {
            return new ValidationResult(false, decision, reason, pm);
        }
    }
}
