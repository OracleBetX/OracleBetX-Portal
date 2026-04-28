package com.oraclebet.portal.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
public class MongoConfig {

    @Bean
    public MongoDatabase mongoDatabase(MongoClient mongoClient,
                                        @Value("${spring.data.mongodb.database}") String dbName) {
        return mongoClient.getDatabase(dbName);
    }

    /**
     * 强制 MongoDatabaseFactory 用 spring.data.mongodb.database 配的库名。
     *
     * <p>不 override 时 Spring Boot auto-config 会从 URI path 部分取库名（URI 是 .../admin），
     * 但有些 driver 行为（写入未指定 db 的集合时）会回退到 default 'test' 库 — 实测 portal
     * 写 bot_product_binding 落到 test 库而不是 x-bet，导致 LPBot 看不到 binding。
     */
    @Bean
    @Primary
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient,
                                                     @Value("${spring.data.mongodb.database}") String dbName) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, dbName);
    }

    @Bean
    @Primary
    public MongoTemplate mongoTemplate(MongoDatabaseFactory factory) {
        return new MongoTemplate(factory);
    }
}
