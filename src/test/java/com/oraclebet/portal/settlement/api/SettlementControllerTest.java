package com.oraclebet.portal.settlement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.accountengine.api.AccountEngineUserApi;
import com.oraclebet.accountengine.api.dto.AccountEngineUserDto;
import com.oraclebet.catalog.api.ProductCatalogApi;
import com.oraclebet.catalog.dto.InstrumentDto;
import com.oraclebet.catalog.dto.MarketDto;
import com.oraclebet.catalog.dto.ProductRootDto;
import com.oraclebet.portal.settlement.dto.SettleMarketRequest;
import com.oraclebet.portal.settlement.pm.SettlementPmValidationService;
import com.oraclebet.portal.settlement.service.EventSettlementService;
import com.oraclebet.support.apikit.ApiResponse;
import com.oraclebet.support.apikit.ApiResponseFactory;
import com.oraclebet.support.apikit.ErrorCodeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class SettlementControllerTest {

    private EventSettlementService settlementService;
    private AccountEngineUserApi accountEngineUserApi;
    private ProductCatalogApi productCatalogApi;
    private SettlementPmValidationService pmValidationService;
    private SettlementController controller;

    @BeforeEach
    void setUp() {
        settlementService = mock(EventSettlementService.class);
        accountEngineUserApi = mock(AccountEngineUserApi.class);
        productCatalogApi = mock(ProductCatalogApi.class);
        pmValidationService = mock(SettlementPmValidationService.class);
        ApiResponseFactory responseFactory = new ApiResponseFactory(
                List.of(),
                List.of(),
                code -> Optional.<ErrorCodeRegistry.ErrorDef>empty());
        controller = new SettlementController(
                settlementService,
                accountEngineUserApi,
                responseFactory,
                productCatalogApi,
                new ObjectMapper(),
                pmValidationService);
    }

    @Test
    void settle_blocksBeforeStatusUpdateAndSettlementWhenPmValidationFails() {
        SettleMarketRequest req = request();
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(productCatalogApi.findByLegacyEventId("evt-1")).thenReturn(Optional.of(product("OPEN")));
        when(pmValidationService.validate(req, null)).thenReturn(
                new SettlementPmValidationService.ValidationResult(false, "BLOCK_MISMATCH", "mismatch", null));

        ApiResponse<Object> response = controller.settle(httpReq, req);

        assertFalse(response.ok());
        verify(productCatalogApi, never()).updateProductRootStatus("pr:EV:evt-1", "SETTLED");
        verify(settlementService, never()).settleMarket(
                "evt-1", "mkt-1", "1", "USDT", "CASH", "batch-1", "lp-1");
    }

    @Test
    void settle_productNotFoundReturnsFailureBeforeValidation() {
        SettleMarketRequest req = request();
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(productCatalogApi.findByLegacyEventId("evt-1")).thenReturn(Optional.empty());

        ApiResponse<Object> response = controller.settle(httpReq, req);

        assertFalse(response.ok());
        verify(pmValidationService, never()).validate(req, null);
        verify(productCatalogApi, never()).updateProductRootStatus(anyString(), anyString());
        verify(settlementService, never()).settleMarket(
                "evt-1", "mkt-1", "1", "USDT", "CASH", "batch-1", "lp-1");
    }

    @Test
    void settle_lpUserMissingDoesNotMarkSettled() {
        SettleMarketRequest req = request();
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(productCatalogApi.findByLegacyEventId("evt-1")).thenReturn(Optional.of(product("OPEN")));
        stubBinaryMarket();
        when(pmValidationService.validate(req, null)).thenReturn(
                new SettlementPmValidationService.ValidationResult(true, "WARN_MATCH", null, null));
        when(accountEngineUserApi.findByEmail("evt-1_mkt-1_2_bot@xbet.com")).thenReturn(Optional.empty());

        ApiResponse<Object> response = controller.settle(httpReq, req);

        assertFalse(response.ok());
        verify(productCatalogApi, never()).updateProductRootStatus(anyString(), anyString());
        verify(settlementService, never()).settleMarket(
                "evt-1", "mkt-1", "1", "USDT", "CASH", "batch-1", "lp-1");
    }

    @Test
    void settle_settlementFailureDoesNotMarkSettled() {
        SettleMarketRequest req = request();
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(productCatalogApi.findByLegacyEventId("evt-1")).thenReturn(Optional.of(product("OPEN")));
        stubBinaryMarket();
        when(pmValidationService.validate(req, null)).thenReturn(
                new SettlementPmValidationService.ValidationResult(true, "WARN_MATCH", null, null));
        AccountEngineUserDto user = new AccountEngineUserDto();
        user.setUserId("lp-1");
        when(accountEngineUserApi.findByEmail("evt-1_mkt-1_2_bot@xbet.com")).thenReturn(Optional.of(user));
        doThrow(new IllegalStateException("settlement failed")).when(settlementService)
                .settleMarket("evt-1", "mkt-1", "1", "USDT", "CASH", "batch-1", "lp-1");

        try {
            controller.settle(httpReq, req);
        } catch (IllegalStateException expected) {
            // expected
        }

        verify(productCatalogApi, never()).updateProductRootStatus(anyString(), anyString());
    }

    @Test
    void settle_runsSettlementWhenPmValidationAllows() {
        SettleMarketRequest req = request();
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(productCatalogApi.findByLegacyEventId("evt-1")).thenReturn(Optional.of(product("OPEN")));
        stubBinaryMarket();
        when(pmValidationService.validate(req, null)).thenReturn(
                new SettlementPmValidationService.ValidationResult(true, "WARN_MATCH", null, null));
        AccountEngineUserDto user = new AccountEngineUserDto();
        user.setUserId("lp-1");
        when(accountEngineUserApi.findByEmail("evt-1_mkt-1_2_bot@xbet.com")).thenReturn(Optional.of(user));
        when(productCatalogApi.findMarketsByProductRootId("pr:EV:evt-1")).thenReturn(List.of(market("SETTLED")));

        ApiResponse<Object> response = controller.settle(httpReq, req);

        assertTrue(response.ok());
        verify(pmValidationService).validate(req, null);
        verify(productCatalogApi).updateMarketStatusByLegacyId("evt-1", "mkt-1", "SETTLED");
        verify(productCatalogApi).updateProductRootStatus("pr:EV:evt-1", "SETTLED");
        verify(settlementService).settleMarket("evt-1", "mkt-1", "1", "USDT", "CASH", "batch-1", "lp-1");
    }

    @Test
    void settle_marketSettledDoesNotMarkProductWhenOtherMarketsOpen() {
        SettleMarketRequest req = request();
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(productCatalogApi.findByLegacyEventId("evt-1")).thenReturn(Optional.of(product("OPEN")));
        stubBinaryMarket();
        when(pmValidationService.validate(req, null)).thenReturn(
                new SettlementPmValidationService.ValidationResult(true, "WARN_MATCH", null, null));
        AccountEngineUserDto user = new AccountEngineUserDto();
        user.setUserId("lp-1");
        when(accountEngineUserApi.findByEmail("evt-1_mkt-1_2_bot@xbet.com")).thenReturn(Optional.of(user));
        MarketDto settled = market("SETTLED");
        MarketDto open = market("ACTIVE");
        open.setId("mk:EV:evt-1:mkt-2");
        open.setLegacyMarketId("mkt-2");
        when(productCatalogApi.findMarketsByProductRootId("pr:EV:evt-1")).thenReturn(List.of(settled, open));

        ApiResponse<Object> response = controller.settle(httpReq, req);

        assertTrue(response.ok());
        verify(productCatalogApi).updateMarketStatusByLegacyId("evt-1", "mkt-1", "SETTLED");
        verify(productCatalogApi, never()).updateProductRootStatus("pr:EV:evt-1", "SETTLED");
    }

    private static ProductRootDto product(String status) {
        ProductRootDto dto = new ProductRootDto();
        dto.setId("pr:EV:evt-1");
        dto.setStatus(status);
        return dto;
    }

    private void stubBinaryMarket() {
        when(productCatalogApi.findMarketsByProductRootId("pr:EV:evt-1")).thenReturn(List.of(market("ACTIVE")));
        when(productCatalogApi.findInstrumentsByMarketId("mk:EV:evt-1:mkt-1")).thenReturn(List.of(
                instrument("1"),
                instrument("2")
        ));
    }

    private static MarketDto market(String status) {
        MarketDto market = new MarketDto();
        market.setId("mk:EV:evt-1:mkt-1");
        market.setProductRootId("pr:EV:evt-1");
        market.setLegacyEventId("evt-1");
        market.setLegacyMarketId("mkt-1");
        market.setStatus(status);
        return market;
    }

    private static InstrumentDto instrument(String selectionId) {
        InstrumentDto instrument = new InstrumentDto();
        instrument.setMarketId("mk:EV:evt-1:mkt-1");
        instrument.setLegacyEventId("evt-1");
        instrument.setLegacyMarketId("mkt-1");
        instrument.setLegacySelectionId(selectionId);
        return instrument;
    }

    private static SettleMarketRequest request() {
        SettleMarketRequest req = new SettleMarketRequest();
        req.setEventId("evt-1");
        req.setMarketId("mkt-1");
        req.setWinnerSelectionId("1");
        req.setCurrency("USDT");
        req.setAccountType("CASH");
        req.setSettleBatchId("batch-1");
        return req;
    }
}
