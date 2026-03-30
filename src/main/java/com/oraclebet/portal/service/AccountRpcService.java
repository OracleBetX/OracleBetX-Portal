package com.oraclebet.portal.service;

import com.oraclebet.portal.config.AccountRpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * AccountEngine RPC 客户端。
 */
@Service
public class AccountRpcService {

    private static final Logger log = LoggerFactory.getLogger(AccountRpcService.class);

    private final RestTemplate restTemplate;
    private final AccountRpcProperties props;

    public AccountRpcService(RestTemplate accountRpcRestTemplate, AccountRpcProperties props) {
        this.restTemplate = accountRpcRestTemplate;
        this.props = props;
    }

    /**
     * 注册用户（幂等）。
     */
    public Map<String, Object> signUp(String email, String password, String code) {
        String url = props.getPathPrefix() + "/users/sign-up";
        Map<String, String> body = Map.of("email", email, "password", password, "code", code);
        log.info("[account-rpc] signUp email={}", email);
        return restTemplate.postForObject(url, body, Map.class);
    }

    /**
     * 按邮箱查用户。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findByEmail(String email) {
        String url = props.getPathPrefix() + "/users/by-email?email=" + email;
        try {
            return restTemplate.getForObject(url, Map.class);
        } catch (Exception e) {
            log.warn("[account-rpc] findByEmail failed email={}", email);
            return null;
        }
    }
}
