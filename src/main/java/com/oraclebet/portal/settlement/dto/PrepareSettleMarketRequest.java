package com.oraclebet.portal.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PrepareSettleMarketRequest {

    @NotBlank
    private String eventId;

    @NotBlank
    private String marketId;
}