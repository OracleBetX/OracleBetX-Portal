package com.oraclebet.portal.settlement.service;

import com.oraclebet.accountengine.api.AccountEngineReservationQueryApi;
import com.oraclebet.portal.settlement.entity.ReservationState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationQueryServiceImpl implements ReservationQueryService {

    private final AccountEngineReservationQueryApi accountEngineReservationQueryApi;

    @Override
    public ReservationState getState(UUID reservationId) {
        if (reservationId == null) {
            return ReservationState.UNKNOWN;
        }

        String id = reservationId.toString();
        String state = accountEngineReservationQueryApi.findReservationState(id);
        if (state == null || state.isBlank()) {
            return ReservationState.UNKNOWN;
        }
        return switch (state) {
            case "ACTIVE", "PROCESSING", "SUCCESS" -> ReservationState.OPEN;
            case "COMMITTED" -> ReservationState.COMMITTED;
            case "RELEASED" -> ReservationState.RELEASED;
            default -> ReservationState.UNKNOWN;
        };

    }

    @Override
    public String debugInfo(UUID reservationId) {
        return reservationId == null ? "reservationId=null" : "reservationId=" + reservationId;
    }
}
