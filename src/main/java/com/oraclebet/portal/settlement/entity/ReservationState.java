package com.oraclebet.portal.settlement.entity;

/** RSV 冻结状态（你们已有：OPEN/RELEASED/COMMITTED） */
/**
 * 冻结凭证（Reservation / RSV）的生命周期状态。
 *
 * 背景：
 * - Reservation 是资金/持仓“冻结”的抽象，用于实现
 *   先冻结（RESERVE）→ 后撮合 → 成交提交（COMMIT）/ 撤单释放（RELEASE）的模型。
 * - 在你的架构里，Ledger/Position Engine 是资金真相，
 *   订单运行态（order_hold 等）只是投影；对账收敛时应以 RSV 状态为准。
 *
 * 状态机约束：
 * - 只允许单向流转，不允许回退；
 * - OPEN 为唯一中间态；
 * - RELEASED 与 COMMITTED 为互斥终态。
 *
 * 典型流转：
 *   OPEN  --release-->  RELEASED   （撤单/失败解冻，终态）
 *   OPEN  --commit-->   COMMITTED  （成交扣账/扣仓，终态）
 */
public enum ReservationState {

    /**
     * OPEN：冻结中（唯一中间态）。
     *
     * 含义：
     * - 资金或持仓已成功冻结；
     * - 尚未完成成交提交或释放；
     * - 允许后续执行 commit 或 release。
     *
     * 对账建议：
     * - 若订单显示已撤但 RSV 仍 OPEN，说明取消链路未完全收敛；
     * - 若订单显示已成交但 RSV 仍 OPEN，说明结算未完全收敛。
     */
    OPEN,

    /**
     * RELEASED：已释放冻结（终态）。
     *
     * 含义：
     * - 冻结资源已全部释放；
     * - 对应订单应收敛为 CANCELLED / CLOSED 等非成交终态；
     * - 不允许再执行 commit。
     *
     * 幂等原则：
     * - 重复 release 应通过 idemKey 保证安全无副作用。
     */
    RELEASED,

    /**
     * COMMITTED：已提交冻结（终态）。
     *
     * 含义：
     * - 冻结资源已被提交（扣账/扣仓）；
     * - 通常发生在成交结算阶段；
     * - 对应订单应收敛为 FILLED / CLOSED 等成交终态；
     * - 不允许再执行 release。
     *
     * 幂等原则：
     * - 重复 commit 应复用结果，不产生二次扣账。
     */
    COMMITTED,

    /**
     * UNKNOWN：未知状态。
     *
     * 含义：
     * - 无法查询到 reservation；
     * - 数据延迟、路由错误、分片错误；
     * - 或状态未映射。
     *
     * 处理建议：
     * - 进入自动重试队列；
     * - 超过阈值升级为人工工单；
     * - 必须记录 reservationId / orderId / shardKey 便于排查。
     */
    UNKNOWN
}