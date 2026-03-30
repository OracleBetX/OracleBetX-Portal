package com.oraclebet.portal.api;

import com.oraclebet.discovery.model.DiscoveryNodeType;
import com.oraclebet.discovery.nacos.rpc.NodeRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LP 用户管理接口（代理到 MatchEngine）。
 *
 * <pre>
 * POST /api/lp/user/create?eventId=xxx&marketId=1  — 创建 LP 机器人用户
 * GET  /api/lp/user/check?eventId=xxx&marketId=1   — 检查 LP 用户是否存在
 * </pre>
 */
@RestController
@RequestMapping("/api/lp")
public class LpUserController {

    private static final Logger log = LoggerFactory.getLogger(LpUserController.class);

    private final NodeRpcClient nodeRpcClient;

    public LpUserController(NodeRpcClient nodeRpcClient) {
        this.nodeRpcClient = nodeRpcClient;
    }

    @PostMapping("/user/create")
    public ResponseEntity<Map> createLpUser(@RequestParam String eventId,
                                             @RequestParam String marketId) {
        log.info("[lp-user] 创建 LP 用户 eventId={} marketId={}", eventId, marketId);

        Map result = nodeRpcClient.post(DiscoveryNodeType.MATCH_ENGINE,
                "/api/lp/user/create?eventId=" + eventId + "&marketId=" + marketId,
                null, Map.class);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/user/check")
    public ResponseEntity<Map> checkLpUser(@RequestParam String eventId,
                                            @RequestParam String marketId) {
        Map result = nodeRpcClient.get(DiscoveryNodeType.MATCH_ENGINE,
                "/api/lp/user/check?eventId=" + eventId + "&marketId=" + marketId, Map.class);

        return ResponseEntity.ok(result);
    }
}
