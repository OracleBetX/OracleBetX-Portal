package com.oraclebet.portal.api;

import com.oraclebet.discovery.model.DiscoveryNodeType;
import com.oraclebet.discovery.nacos.rpc.NodeRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LP 用户管理接口。
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
    public ResponseEntity<Map<String, Object>> createLpUser(
            @RequestParam String eventId,
            @RequestParam String marketId) {

        String email = lpEmail(eventId, marketId);

        log.info("[lp-user] 创建 LP 用户 eventId={} marketId={} email={}", eventId, marketId, email);

        Map body = Map.of("email", email, "password", "123456", "code", "123456");
        Map user = nodeRpcClient.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                "/api/account/users/sign-up", body, Map.class);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "创建失败"));
        }

        log.info("[lp-user] LP 用户已创建 eventId={} marketId={} userId={}", eventId, marketId, user.get("userId"));

        return ResponseEntity.ok(Map.of(
                "success", true,
                "eventId", eventId,
                "marketId", marketId,
                "email", email,
                "userId", user.getOrDefault("userId", ""),
                "message", "LP 用户已创建"
        ));
    }

    @GetMapping("/user/check")
    public ResponseEntity<Map<String, Object>> checkLpUser(
            @RequestParam String eventId,
            @RequestParam String marketId) {

        String email = lpEmail(eventId, marketId);

        Map user = nodeRpcClient.get(DiscoveryNodeType.ACCOUNT_ENGINE,
                "/api/account/users/by-email?email=" + email, Map.class);

        if (user == null) {
            return ResponseEntity.ok(Map.of("exists", false, "eventId", eventId, "marketId", marketId, "email", email));
        }

        return ResponseEntity.ok(Map.of(
                "exists", true,
                "eventId", eventId,
                "marketId", marketId,
                "email", email,
                "userId", user.getOrDefault("userId", "")
        ));
    }

    private String lpEmail(String eventId, String marketId) {
        return safeToken(eventId) + "_" + safeToken(marketId) + "_bot@xbet.com";
    }

    private String safeToken(String s) {
        if (s == null) return "null";
        return s.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
