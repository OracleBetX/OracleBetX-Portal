package com.oraclebet.portal.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public  class SettleMarketRequest {

    @NotBlank
    private String eventId;

    @NotBlank
    private String marketId;

    @NotBlank
    private String winnerSelectionId;

    /**
     * 币种：如 USDT
     */
    @NotBlank
    private String currency;

    /**
     * 账户类型：如 CASH
     */
    @NotBlank
    private String accountType;

    /**
     * 幂等批次号：必须全局唯一
     * 推荐：SETTLE:{eventId}:{marketId}:{winnerSelectionId}:{yyyyMMddHHmmss}
     */
    @NotBlank
    private String settleBatchId;
}