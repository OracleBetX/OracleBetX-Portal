package com.oraclebet.portal.lp.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class LpInitRequest {
    private String lpUserId;
    private String eventId;
    private String marketId;

    private String homeSelectionId;
    private BigDecimal homePrice;
    private BigDecimal homeQty;

    private String awaySelectionId;
    private BigDecimal awayPrice;
    private BigDecimal awayQty;

    /** 可选：先 credit 注资金额，null/0 则不注资 */
    private BigDecimal initCash;
}
