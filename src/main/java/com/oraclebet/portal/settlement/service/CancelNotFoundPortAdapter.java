package com.oraclebet.portal.settlement.service;

import com.oraclebet.modules.boundary.CancelNotFoundPort;
import com.oraclebet.portal.settlement.entity.CancelNotFoundInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelNotFoundPortAdapter implements CancelNotFoundPort {
    private final ReconcileService reconcileService;

    @Override
    public void onCancelNotFound(CancelNotFoundRequest request) {
        reconcileService.onCancelNotFound(new CancelNotFoundInput(
                request.orderId(),
                request.reservationId(),
                request.productId(),
                request.userId(),
                request.engineInstanceId(),
                request.shardKey(),
                request.traceId(),
                request.operationId(),
                request.errorMessage()
        ));
    }
}
