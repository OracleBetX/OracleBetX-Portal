package com.oraclebet.portal.settlement.service;

import com.oraclebet.common.enums.OrderStatus;
import com.oraclebet.accountengine.api.AccountEngineSettleOrderApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 基于 exchange order_entity 的订单状态访问实现。
 */
@Service
@RequiredArgsConstructor
public class JpaOrderStateRepository implements OrderStateRepository {

    private final AccountEngineSettleOrderApi accountEngineSettleOrderApi;

    @Override
    public Optional<OrderStateView> findByOrderId(UUID orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return accountEngineSettleOrderApi.findById(orderId.toString())
                .map(order -> toView(order, orderId));
    }

    @Override
    public boolean moveToCancelled(UUID orderId, String reason) {
        return moveTo(orderId, OrderStatus.CANCELLED);
    }

    @Override
    public boolean moveToFilled(UUID orderId, String reason) {
        return moveTo(orderId, OrderStatus.FILLED);
    }

    @Override
    public boolean moveToClosed(UUID orderId, String reason) {
        // 现有 OrderStatus 没有 CLOSED，使用 EXPIRED 作为“关闭”终态兜底。
        return moveTo(orderId, OrderStatus.EXPIRED);
    }

    private boolean moveTo(UUID orderId, OrderStatus target) {
        if (orderId == null) {
            return false;
        }
        return accountEngineSettleOrderApi.moveStatus(orderId.toString(), target.name());
    }

    private OrderStateView toView(com.oraclebet.accountengine.api.dto.AccountEngineSettleOrderDto order,
                                  UUID fallbackOrderId) {
        UUID reservationId = null;
        String rawReservationId = order.getReservationId();
        if (rawReservationId != null && !rawReservationId.isBlank()) {
            try {
                reservationId = UUID.fromString(rawReservationId);
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 格式时保持 null，避免影响对账主流程。
            }
        }
        UUID normalizedOrderId = fallbackOrderId;
        if (order.getOrderId() != null && !order.getOrderId().isBlank()) {
            try {
                normalizedOrderId = UUID.fromString(order.getOrderId());
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 订单号时保留调用方传入值。
            }
        }
        return new OrderStateView(
                normalizedOrderId,
                order.getStatus() == null ? "UNKNOWN" : order.getStatus(),
                order.getEventId(),
                order.getUserId(),
                reservationId
        );
    }
}
