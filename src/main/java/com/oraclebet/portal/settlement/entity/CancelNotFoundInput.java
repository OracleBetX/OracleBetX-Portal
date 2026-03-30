package com.oraclebet.portal.settlement.entity;

import java.util.UUID;

/** Cancel 异常上报输入（比如 matchingengine 或 cancel controller 调用） */
public record CancelNotFoundInput(
        String orderId,
        String reservationId,
        String productId,
        String userId,
        String engineInstanceId,
        String shardKey,
        String traceId,
        String operationId,
        String errorMessage
) {}