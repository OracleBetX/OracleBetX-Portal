package com.oraclebet.portal.settlement.entity;

/**
 * 结算结果枚举（Settlement Outcome）。
 *
 * 用途：
 * - 表示订单在赛事最终判定后的“业务结果”；
 * - 用于驱动结算引擎计算盈亏与资金变动；
 * - 不直接等同于资金动作（具体资金操作仍由 Ledger/Position Engine 执行）。
 *
 * 说明：
 * - 该枚举属于“业务语义层”；
 * - 资金层会根据该结果决定 commit / release / credit 等操作；
 * - 与 ReservationState（冻结状态）不同。
 */
public enum SettlementOutcome {

    /**
     * NO_SETTLEMENT：未结算。
     *
     * 场景：
     * - 比赛尚未结束；
     * - 盘口未触发结算；
     * - 或该订单暂不参与当前结算批次。
     *
     * 资金处理：
     * - 不发生任何变动；
     * - 保持原有冻结状态。
     */
    NO_SETTLEMENT,

    /**
     * WIN：赢。
     *
     * 场景：
     * - 订单命中赛事结果；
     * - 应按赔率计算盈利。
     *
     * 资金处理：
     * - 提交冻结（commit）；
     * - 返还本金并增加盈利。
     */
    WIN,

    /**
     * LOSE：输。
     *
     * 场景：
     * - 订单未命中结果。
     *
     * 资金处理：
     * - 提交冻结（commit）；
     * - 不返还本金。
     */
    LOSE,

    /**
     * PUSH：走水 / 打平。
     *
     * 场景：
     * - 无输赢结果；
     * - 比如盘口与实际结果完全一致。
     *
     * 资金处理：
     * - 通常退还本金；
     * - 不产生盈亏。
     */
    PUSH,

    /**
     * VOID：作废 / 取消。
     *
     * 场景：
     * - 比赛取消；
     * - 盘口无效；
     * - 官方判定作废。
     *
     * 资金处理：
     * - 全额退还本金；
     * - 视实现可能直接 release 冻结。
     */
    VOID
}