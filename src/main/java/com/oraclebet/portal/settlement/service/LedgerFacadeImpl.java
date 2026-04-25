package com.oraclebet.portal.settlement.service;

import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ledger 操作门面（通过网关调 AccountEngine /api/admin/ledger/apply）。
 * 结算操作走管理接口（带 Redis 同步）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerFacadeImpl implements LedgerFacade {

    private final GatewayAddressProvider gatewayAddressProvider;
    private final RestTemplate nodeRpcRestTemplate;

    @Override
    public void credit(String userId, String currency, String accountType,
                       BigDecimal amount, String refKey, String idemKey, String reason) {
        Map<String, Object> cmd = buildCmd("CREDIT", userId, currency, accountType, amount, idemKey, refKey, reason, Map.of());
        Map result = callLedger(cmd);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new IllegalStateException("credit failed: " + result.get("code") + " " + result.get("message"));
        }
        log.info("LEDGER_CREDIT user={} {} {} amount={} idemKey={} success=true", userId, currency, accountType, amount, idemKey);
    }

    @Override
    public String reserve(String userId, String currency, String accountType,
                          BigDecimal amount, String refKey, String reason) {
        String idemKey = "Settle:" + refKey;
        Map<String, Object> cmd = buildCmd("RESERVE", userId, currency, accountType, amount, idemKey, refKey, reason, Map.of());
        Map result = callLedger(cmd);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new IllegalStateException("reserve failed: " + result.get("code") + " " + result.get("message"));
        }
        log.info("LEDGER_RESERVE user={} {} {} amount={} idemKey={} success=true", userId, currency, accountType, amount, idemKey);
        return refKey;
    }

    @Override
    public void commit(String userId, String reservationId,
                       String currency, String accountType,
                       BigDecimal amount, String idemKey, String reason) {
        Map<String, Object> cmd = buildCmd("COMMIT", userId, currency, accountType, amount, idemKey, reservationId, reason,
                Map.of("reservationId", reservationId));
        Map result = callLedger(cmd);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new IllegalStateException("commit failed: " + result.get("code") + " " + result.get("message"));
        }
        log.info("LEDGER_COMMIT rsvId={} {} {} amount={} idemKey={} success=true",
                reservationId, currency, accountType, amount, idemKey);
    }

    @SuppressWarnings("unchecked")
    private Map callLedger(Map<String, Object> cmd) {
        String url = gatewayAddressProvider.getGatewayUrl() + "/api/admin/ledger/apply";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(cmd, headers), Map.class).getBody();
    }

    private Map<String, Object> buildCmd(String type, String userId, String currency, String accountType,
                                          BigDecimal amount, String idemKey, String refId, String reason,
                                          Map<String, Object> attrs) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", type);
        cmd.put("userId", userId);
        cmd.put("currency", currency);
        cmd.put("accountType", accountType);
        cmd.put("amount", amount);
        cmd.put("idemKey", idemKey);
        cmd.put("refId", refId);
        cmd.put("reason", reason);
        cmd.put("attributes", attrs);
        return cmd;
    }
}
