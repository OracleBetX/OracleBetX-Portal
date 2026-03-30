package com.oraclebet.portal.api;

import com.oraclebet.discovery.model.DiscoveryNodeType;
import com.oraclebet.discovery.nacos.rpc.NodeRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LP 初始化接口（代理到 MatchEngine）。
 *
 * <pre>
 * POST /api/lp/init — LP 初始化（冻结资金 + 创建仓位）
 * </pre>
 *
 * <p>实际逻辑在 MatchEngine 的 LpInitService 里，
 * Portal 只做请求转发，保证事务完整性由 MatchEngine 保证。
 */
@RestController
@RequestMapping("/api/lp")
public class LpInitController {

    private static final Logger log = LoggerFactory.getLogger(LpInitController.class);

    private final NodeRpcClient nodeRpcClient;

    public LpInitController(NodeRpcClient nodeRpcClient) {
        this.nodeRpcClient = nodeRpcClient;
    }

    /**
     * LP 初始化（主队 + 客队）。
     * 转发到 MatchEngine /api/lp/init。
     */
    @PostMapping("/init")
    public ResponseEntity<Map> initLp(@RequestBody Map<String, Object> request) {
        log.info("[lp-init] 转发到 MatchEngine lpUserId={} eventId={} marketId={}",
                request.get("lpUserId"), request.get("eventId"), request.get("marketId"));

        Map result = nodeRpcClient.post(DiscoveryNodeType.MATCH_ENGINE,
                "/api/lp/init", request, Map.class);

        return ResponseEntity.ok(result);
    }
}
