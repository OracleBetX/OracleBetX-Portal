package com.oraclebet.portal.api;

import com.oraclebet.portal.lp.dto.LpInitRequest;
import com.oraclebet.portal.lp.dto.LpInitResponse;
import com.oraclebet.portal.lp.entity.LpInitStateEntity;
import com.oraclebet.portal.lp.service.LpInitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * LP 初始化接口。
 *
 * <pre>
 * POST /api/lp/init — LP 初始化（注资 + 冻结 + 扣款）
 * </pre>
 */
@RestController
@RequestMapping("/api/lp")
public class LpInitController {

    private static final Logger log = LoggerFactory.getLogger(LpInitController.class);

    private final LpInitService lpInitService;

    public LpInitController(LpInitService lpInitService) {
        this.lpInitService = lpInitService;
    }

    @PostMapping("/init")
    public ResponseEntity<LpInitResponse> initLp(@RequestBody LpInitRequest request) {
        log.info("[lp-init] lpUserId={} eventId={} marketId={}",
                request.getLpUserId(), request.getEventId(), request.getMarketId());

        LpInitStateEntity state = lpInitService.initLp(request);

        LpInitResponse resp = new LpInitResponse(
                request.getLpUserId(),
                request.getEventId(),
                request.getMarketId(),
                state.getStatus().name(),
                state.getReservationId(),
                state.getMessage()
        );

        return ResponseEntity.ok(resp);
    }
}
