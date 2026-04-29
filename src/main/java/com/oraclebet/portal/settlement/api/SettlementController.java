package com.oraclebet.portal.settlement.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.accountengine.api.AccountEngineUserApi;
import com.oraclebet.catalog.api.ProductCatalogApi;
import com.oraclebet.catalog.dto.ProductRootDto;
import com.oraclebet.contracts.sportprice.model.SettlementStatusMessage;
import com.oraclebet.portal.settlement.dto.PrepareSettleMarketRequest;
import com.oraclebet.portal.settlement.dto.ProductErrorCodes;
import com.oraclebet.portal.settlement.dto.SettleMarketRequest;
import com.oraclebet.portal.settlement.pm.SettlementPmValidationService;
import com.oraclebet.portal.settlement.service.EventSettlementService;
import com.oraclebet.support.apikit.ApiResponse;
import com.oraclebet.support.apikit.ApiResponseFactory;
import com.oraclebet.web.model.CommonErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settlement")
public class SettlementController {

    private final EventSettlementService settlementService;
    private final AccountEngineUserApi accountEngineUserApi;
    private final ApiResponseFactory apiResponseFactory;
    private final ProductCatalogApi productCatalogApi;
    private final ObjectMapper objectMapper;
    private final SettlementPmValidationService pmValidationService;

    @PostMapping("/prepare-settle")
    public ApiResponse<Object> prepareSettle(HttpServletRequest baseReq,
                                             @RequestBody @Valid PrepareSettleMarketRequest req) {

        ProductRootDto product = productCatalogApi.findByLegacyEventId(req.getEventId()).orElse(null);
        if (product == null) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.BAD_REQUEST,
                    ProductErrorCodes.PRODUCT_NOT_FOUND,
                    "product not found",
                    false,
                    Map.of("eventId", req.getEventId())
            );
        }

        // 已经结算过，不能再 prepare
        if ("SETTLED".equals(product.getStatus())) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.CONFLICT,
                    ProductErrorCodes.PRODUCT_NOT_FOUND,
                    "product already settled",
                    false,
                    Map.of(
                            "productId", product.getId(),
                            "eventId", req.getEventId(),
                            "status", product.getStatus()
                    )
            );
        }

        // 只有 OPEN 状态才能进入 prepare
        if (!"OPEN".equals(product.getStatus())) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.CONFLICT,
                    ProductErrorCodes.PRODUCT_STATUS_INVALID,
                    "product status not allowed for prepare-settle",
                    false,
                    Map.of(
                            "productId", product.getId(),
                            "eventId", req.getEventId(),
                            "status", product.getStatus()
                    )
            );
        }

        // ⭐ 核心：改为“预备结算态”
        
        productCatalogApi.updateProductRootStatus(product.getId(), "PREPARING_SETTLEMENT");

        return apiResponseFactory.ok(baseReq, Map.of(
                "status", "PREPARED",
                "eventId", req.getEventId(),
                "marketId", req.getMarketId(),
                "productStatus", product.getStatus()
        ));
    }

    /**
     * 一个接口：结算一个 market（eventId + marketId）
     * <p>
     * 例子：
     * POST /api/settlement/settle
     * {
     * "eventId": "EVT123",
     * "marketId": "MKT1",
     * "winnerSelectionId": "HOME",
     * "currency": "USDT",
     * "accountType": "CASH",
     * "settleBatchId": "SETTLE:EVT123:MKT1:HOME:20260119"
     * }
     */
    @PostMapping("/settle")
    public ApiResponse<Object> settle(HttpServletRequest baseReq, @RequestBody @Valid SettleMarketRequest req) {

        long start = System.currentTimeMillis();


        ProductRootDto product = productCatalogApi.findByLegacyEventId(req.getEventId()).orElse(null);
        if (product == null) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.BAD_REQUEST,
                    ProductErrorCodes.PRODUCT_NOT_FOUND,
                    "product not found",
                    false,
                    Map.of("eventId", req.getEventId())
            );
        }
        if ("SETTLED".equals(product.getStatus())) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.BAD_REQUEST,
                    ProductErrorCodes.PRODUCT_NOT_FOUND,
                    "product is closed",
                    false,
                    Map.of(
                            "productId", product.getId(),
                            "eventId", req.getEventId(),
                            "status", product.getStatus()
                    )
            );
        }



//        // 构造 SETTLING 状态消息
//        SettlementStatusMessage msg =
//                SettlementStatusMessage.settling(
//                        req.getEventId(),
//                        req.getMarketId(),
//                        req.getWinnerSelectionId()
//                );
//
//        try {
//            String json = objectMapper.writeValueAsString(msg);
//            topicOjb.publish(json);
//        } catch (Exception e) {
//            log.error(
//                    "publish settlement SETTLING failed, eventId={}, selectId={}",
//                    req.getEventId(),
//                    req.getWinnerSelectionId(),
//                    e
//            );
//
//        }



        SettlementPmValidationService.ValidationResult pmValidation = pmValidationService.validate(
                req,
                baseReq.getHeader("X-Admin-Actor"));
        if (!pmValidation.allowed()) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.CONFLICT,
                    CommonErrorCodes.INVALID_ARGUMENT,
                    "PM settlement validation failed",
                    false,
                    Map.of(
                            "eventId", req.getEventId(),
                            "marketId", req.getMarketId(),
                            "winnerSelectionId", req.getWinnerSelectionId(),
                            "decision", pmValidation.decision(),
                            "reason", pmValidation.reason() == null ? "" : pmValidation.reason()
                    )
            );
        }

        log.info("[API][SETTLE] start eventId={}, marketId={}, winner={}, batchId={}, currency={}, accountType={}",
                req.getEventId(), req.getMarketId(), req.getWinnerSelectionId(),
                req.getSettleBatchId(), req.getCurrency(), req.getAccountType());

        String loserSelectionId = resolveSingleLoserSelectionId(product.getId(), req.getEventId(), req.getMarketId(), req.getWinnerSelectionId());
        if (loserSelectionId == null) {
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.CONFLICT,
                    CommonErrorCodes.INVALID_ARGUMENT,
                    "settlement payer selection cannot be resolved",
                    false,
                    Map.of(
                            "eventId", req.getEventId(),
                            "marketId", req.getMarketId(),
                            "winnerSelectionId", req.getWinnerSelectionId(),
                            "reason", "expected exactly one non-winning selection in market"
                    )
            );
        }

        String email = lpEmail(req.getEventId(), req.getMarketId(), loserSelectionId);
        var userIdValue = accountEngineUserApi.findByEmail(email);
        if (userIdValue.isEmpty()) {
            log.info("[API][SETTLE] User with email={} not found", email);
            return apiResponseFactory.fail(
                    baseReq,
                    HttpStatus.BAD_REQUEST,
                    CommonErrorCodes.INVALID_ARGUMENT,
                    "settlement LP user not found",
                    false,
                    Map.of(
                            "eventId", req.getEventId(),
                            "marketId", req.getMarketId(),
                            "payerSelectionId", loserSelectionId,
                            "email", email
                    )
            );
        }


        String userId = String.valueOf(userIdValue.get().getUserId());

        settlementService.settleMarket(
                req.getEventId(),
                req.getMarketId(),
                req.getWinnerSelectionId(),
                req.getCurrency(),
                req.getAccountType(),
                req.getSettleBatchId(),userId
        );

        productCatalogApi.updateMarketStatusByLegacyId(req.getEventId(), req.getMarketId(), "SETTLED");
        if (allMarketsSettled(product.getId())) {
            productCatalogApi.updateProductRootStatus(product.getId(), "SETTLED");
        }

        long costMs = System.currentTimeMillis() - start;

        log.info("[API][SETTLE] done eventId={}, marketId={}, winner={}, batchId={}, costMs={}",
                req.getEventId(), req.getMarketId(), req.getWinnerSelectionId(),
                req.getSettleBatchId(), costMs);

        return apiResponseFactory.ok(baseReq, Map.of(
                "status", "DONE",
                "eventId", req.getEventId(),
                "marketId", req.getMarketId(),
                "winnerSelectionId", req.getWinnerSelectionId(),
                "settleBatchId", req.getSettleBatchId(),
                "serverTs", Instant.now().toEpochMilli(),
                "costMs", costMs
        ));
    }

    private String resolveSingleLoserSelectionId(String productRootId, String eventId, String marketId, String winnerSelectionId) {
        var market = productCatalogApi.findMarketsByProductRootId(productRootId).stream()
                .filter(m -> Objects.equals(m.getLegacyEventId(), eventId))
                .filter(m -> Objects.equals(m.getLegacyMarketId(), marketId))
                .findFirst()
                .orElse(null);
        if (market == null) {
            return null;
        }
        List<String> losers = productCatalogApi.findInstrumentsByMarketId(market.getId()).stream()
                .map(i -> i.getLegacySelectionId())
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> !Objects.equals(s, winnerSelectionId))
                .distinct()
                .toList();
        return losers.size() == 1 ? losers.get(0) : null;
    }

    private boolean allMarketsSettled(String productRootId) {
        List<com.oraclebet.catalog.dto.MarketDto> markets = productCatalogApi.findMarketsByProductRootId(productRootId);
        return !markets.isEmpty() && markets.stream().allMatch(m -> "SETTLED".equals(m.getStatus()));
    }

    private String lpEmail(String eventId, String marketId, String selectionId) {
        // 防止 @Email 校验不过：去掉空格、把可能的非法字符替换成 _
        String e = safeToken(eventId);
        String m = safeToken(marketId);
        String s = safeToken(selectionId);
        return e + "_" + m + "_" + s + "_bot@xbet.com";
    }

    private String safeToken(String s) {
        if (s == null) return "null";
        // 邮箱 local-part 允许很多字符，但为了稳，统一只保留字母数字下划线短横线点
        return s.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

}
