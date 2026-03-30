package com.oraclebet.portal.settlement.service;

import java.util.Optional;
import java.util.UUID;

/**
 * 你们现有订单运行态表（比如 order_hold / exchange_order）
 * 这里抽象出 reconcile 需要的最小能力：
 * - 查订单状态
 * - 把订单推进到终态（CANCELLED / FILLED / CLOSED）
 */
public interface OrderStateRepository {

    Optional<OrderStateView> findByOrderId(UUID orderId);

    /**
     * 把订单落到终态（必须幂等：重复调用不应报错）
     * @return true 表示确实发生了状态变更；false 表示无需变更（已是目标状态）
     */
    boolean moveToCancelled(UUID orderId, String reason);

    boolean moveToFilled(UUID orderId, String reason);

    boolean moveToClosed(UUID orderId, String reason);

    record OrderStateView(UUID orderId, String orderState, String productId, String userId, UUID reservationId) {}
}
