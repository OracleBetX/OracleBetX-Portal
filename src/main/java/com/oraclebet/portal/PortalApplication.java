package com.oraclebet.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.oraclebet.portal",
        "com.oraclebet.support.apikit",
        "com.oraclebet.common.config.mongodb",
        "com.oraclebet.discovery",
        "com.oraclebet.matchengine.marketdata.repository"
})
@ConfigurationPropertiesScan(basePackages = "com.oraclebet")
public class PortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
