package com.oraclebet.portal.settlement.entity;

/**
 * 异常流水中的“动作类型”（order_anomaly_log.action）。
 *
 * 设计目的：
 * - 用于标识“异常发生在哪个业务动作阶段”，而不是表示最终订单结果；
 * - 便于按阶段统计异常比例（例如：取消异常多？还是写库异常多？）；
 * - 结合 error_code 一起定位问题来源。
 *
 * 注意：
 * - action 表示“发生异常时的业务动作上下文”；
 * - 不等同于订单状态（OPEN/CANCELLED/FILLED）；
 * - 也不等同于 ReconcileReason（触发自动收敛的原因）。
 */
public enum AnomalyAction {

    /**
     * CANCEL：取消相关动作阶段。
     *
     * 场景：
     * - 撮合取消时 Order not found；
     * - 取消超时；
     * - 取消链路中断等。
     *
     * 语义：
     * - 异常发生在“取消流程”中。
     */
    CANCEL,

    /**
     * RECONCILE：自动收敛/对账阶段。
     *
     * 场景：
     * - ReconcileWorker 执行过程中出现异常；
     * - RSV 与订单状态不一致；
     * - 自动修复失败。
     *
     * 语义：
     * - 异常发生在“后台收敛修复流程”中。
     */
    RECONCILE,

    /**
     * RELEASE：释放冻结阶段。
     *
     * 场景：
     * - 执行 release(reservationId) 失败；
     * - 冻结凭证状态异常；
     * - 部分释放逻辑异常。
     *
     * 语义：
     * - 异常发生在“冻结释放”操作中。
     */
    RELEASE,

    /**
     * COMMIT：提交冻结（成交结算）阶段。
     *
     * 场景：
     * - 执行 commit(reservationId) 失败；
     * - 成交后扣账异常；
     * - 幂等冲突或资金状态异常。
     *
     * 语义：
     * - 异常发生在“成交结算提交”阶段。
     */
    COMMIT,

    /**
     * ROUTE：路由/分片/实例调度阶段。
     *
     * 场景：
     * - Cancel/Place 发错撮合实例；
     * - product shard 不匹配；
     * - 分区路由错误。
     *
     * 语义：
     * - 异常发生在“请求路由或分片定位”阶段。
     */
    ROUTE,

    /**
     * DB_WRITE：投影/落库阶段。
     *
     * 场景：
     * - 撮合已取消，但更新订单表失败；
     * - 更新影响行数为 0（update=0）；
     * - 数据库异常、事务回滚等。
     *
     * 语义：
     * - 事实已经发生，但“订单投影写库失败”。
     * - 常见于 CANCEL_DB_WRITE_FAIL 这种类型。
     */
    DB_WRITE
}