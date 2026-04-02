package com.oraclebet.portal.lp.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Kafka 异步 LP 初始化消息体（per market）。
 */
@Data
public class LpInitCommand {
    private String eventId;
    private String marketId;

    private String homeSelectionId;
    private BigDecimal homePrice;
    private BigDecimal homeQty;

    private String awaySelectionId;
    private BigDecimal awayPrice;
    private BigDecimal awayQty;

    /** 只有第一条消息带值，其余为 0 */
    private BigDecimal initCash;

    /** 追踪 ID */
    private String traceId;
}
