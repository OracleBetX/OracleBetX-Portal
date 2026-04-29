package com.oraclebet.portal.settlement.pm;

import com.oraclebet.contracts.pm.PmWinningSelectionClient;
import com.oraclebet.contracts.pm.WinningSelectionDto;
import com.oraclebet.portal.settlement.dto.SettleMarketRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementPmValidationServiceTest {

    private SettlementPmValidationProperties props;
    private PmWinningSelectionClient pmClient;
    private SettlementPmAuditRepository auditRepository;
    private SettlementPmValidationService service;

    @BeforeEach
    void setUp() {
        props = new SettlementPmValidationProperties();
        pmClient = mock(PmWinningSelectionClient.class);
        auditRepository = mock(SettlementPmAuditRepository.class);
        service = new SettlementPmValidationService(props, pmClient, auditRepository, new SimpleMeterRegistry());
    }

    @Test
    void off_doesNotCallPmOrAudit() {
        props.setMode(SettlementPmValidationProperties.Mode.OFF);

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertTrue(result.allowed());
        verify(pmClient, never()).getWinningSelection(any(), any());
        verify(auditRepository, never()).insert(any());
    }

    @Test
    void warn_finalMatch_allowsAndAudits() {
        props.setMode(SettlementPmValidationProperties.Mode.WARN);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenReturn(pm("1", WinningSelectionDto.Confidence.FINAL));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertTrue(result.allowed());
        verify(auditRepository).insert(any());
    }

    @Test
    void warn_mismatch_allowsAndAudits() {
        props.setMode(SettlementPmValidationProperties.Mode.WARN);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenReturn(pm("2", WinningSelectionDto.Confidence.FINAL));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertTrue(result.allowed());
        verify(auditRepository).insert(any());
    }

    @Test
    void warn_unavailable_allowsAndAudits() {
        props.setMode(SettlementPmValidationProperties.Mode.WARN);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenReturn(pm(null, WinningSelectionDto.Confidence.UNAVAILABLE));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertTrue(result.allowed());
        verify(auditRepository).insert(any());
    }

    @Test
    void warn_remoteError_allowsAndAudits() {
        props.setMode(SettlementPmValidationProperties.Mode.WARN);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenThrow(new IllegalStateException("down"));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertTrue(result.allowed());
        verify(auditRepository).insert(any());
    }

    @Test
    void enforce_finalMatch_allows() {
        props.setMode(SettlementPmValidationProperties.Mode.ENFORCE);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenReturn(pm("1", WinningSelectionDto.Confidence.FINAL));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertTrue(result.allowed());
        verify(auditRepository).insert(any());
    }

    @Test
    void enforce_mismatch_blocks() {
        props.setMode(SettlementPmValidationProperties.Mode.ENFORCE);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenReturn(pm("2", WinningSelectionDto.Confidence.FINAL));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertFalse(result.allowed());
        verify(auditRepository).insert(any());
    }

    @Test
    void enforce_nonFinal_blocks() {
        props.setMode(SettlementPmValidationProperties.Mode.ENFORCE);
        when(pmClient.getWinningSelection("evt-1", "mkt-1")).thenReturn(pm("1", WinningSelectionDto.Confidence.PROVISIONAL));

        SettlementPmValidationService.ValidationResult result = service.validate(request("1"), "ops");

        assertFalse(result.allowed());
        verify(auditRepository).insert(any());
    }

    private static SettleMarketRequest request(String winnerSelectionId) {
        SettleMarketRequest req = new SettleMarketRequest();
        req.setEventId("evt-1");
        req.setMarketId("mkt-1");
        req.setWinnerSelectionId(winnerSelectionId);
        req.setCurrency("USDT");
        req.setAccountType("CASH");
        req.setSettleBatchId("batch-1");
        return req;
    }

    private static WinningSelectionDto pm(String selectionId, WinningSelectionDto.Confidence confidence) {
        return WinningSelectionDto.builder()
                .eventId("evt-1")
                .marketId("mkt-1")
                .selectionId(selectionId)
                .confidence(confidence)
                .reason(confidence == WinningSelectionDto.Confidence.PROVISIONAL ? "cooldown" : null)
                .build();
    }
}
