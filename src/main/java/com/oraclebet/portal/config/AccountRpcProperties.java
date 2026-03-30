package com.oraclebet.portal.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "account.rpc")
public class AccountRpcProperties {
    private String baseUrl = "http://127.0.0.1:18080";
    private String pathPrefix = "/api/account";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
}
