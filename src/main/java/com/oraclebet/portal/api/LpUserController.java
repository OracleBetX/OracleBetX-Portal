package com.oraclebet.portal.api;

import com.oraclebet.discovery.model.DiscoveryNodeType;
import com.oraclebet.discovery.nacos.rpc.NodeRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LP 用户管理接口。
 *
 * <pre>
 * POST /api/lp/user/create?eventId=xxx&marketId=1&outcomeId=1  — 创建 LP 机器人用户
 * GET  /api/lp/user/check?eventId=xxx&marketId=1&outcomeId=1   — 检查 LP 用户是否存在
 * </pre>
 *
 * 邮箱格式：{eventId}_{marketId}_{outcomeId}_bot@xbet.com
 * 通过 RPC 调 Auth 服务注册用户。
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
                                             @RequestParam String marketId,
                                             @RequestParam(defaultValue = "") String outcomeId) {
        String email = lpEmail(eventId, marketId, outcomeId);
        String password = "123456";

        log.info("[lp-user] 创建 LP 用户 eventId={} marketId={} outcomeId={} email={}",
                eventId, marketId, outcomeId, email);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);

        Map result = nodeRpcClient.post(DiscoveryNodeType.AUTH_NODE,
                "/api/users", body, Map.class);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/user/check")
    public ResponseEntity<Map> checkLpUser(@RequestParam String eventId,
                                            @RequestParam String marketId,
                                            @RequestParam(defaultValue = "") String outcomeId) {
        String email = lpEmail(eventId, marketId, outcomeId);

        Map result = nodeRpcClient.get(DiscoveryNodeType.AUTH_NODE,
                "/api/users/self?email=" + email, Map.class);

        return ResponseEntity.ok(result);
    }

    private String lpEmail(String eventId, String marketId, String outcomeId) {
        String base = safeToken(eventId) + "_" + safeToken(marketId);
        if (outcomeId != null && !outcomeId.isBlank()) {
            base += "_" + safeToken(outcomeId);
        }
        return base + "_bot@xbet.com";
    }

    private String safeToken(String s) {
        if (s == null) return "null";
        return s.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
