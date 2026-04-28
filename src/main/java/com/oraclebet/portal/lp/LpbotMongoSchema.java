package com.oraclebet.portal.lp;

/**
 * Portal 直接写 LPBot 端 MongoDB 集合时硬编码的 Spring Data 元数据。
 *
 * <p>这是已知的跨服务耦合 — portal 直写 lpbot 库（{@code bot_product_binding}），
 * 反序列化路由依赖 {@code _class} 字段。LPBot 重构 model 包路径时**必须**同步改这里。
 *
 * <p>永久解法：lpbot 暴露 {@code POST /admin/bindings/upsert} RPC 端点，portal 改用 RPC，
 * 不再直写 lpbot 库。在那之前，所有引用集中到本类，至少把硬编码字符串收口。
 */
public final class LpbotMongoSchema {

    private LpbotMongoSchema() {}

    /** LPBot 端 BotProductBinding entity 的 Spring Data {@code _class} 字段值。 */
    public static final String BOT_PRODUCT_BINDING_CLASS =
            "com.oracle_bet.LPBot.model.BotProductBinding";
}
