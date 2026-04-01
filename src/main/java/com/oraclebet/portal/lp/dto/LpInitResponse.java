package com.oraclebet.portal.lp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LpInitResponse {
    private String lpUserId;
    private String eventId;
    private String marketId;
    private String status;
    private String reservationId;
    private String message;
}
