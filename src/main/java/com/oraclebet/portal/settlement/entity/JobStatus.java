package com.oraclebet.portal.settlement.entity;

/**
 * 自动收敛任务状态（order_reconcile_job.job_status）。
 *
 * 设计目的：
 * - 描述 ReconcileJob 在后台自动处理流程中的生命周期；
 * - 支持重试、并发控制、失败转人工（Case）等机制；
 * - 避免任务丢失或重复执行导致状态混乱。
 *
 * 注意：
 * - JobStatus 仅表示“任务执行状态”，
 *   不代表订单最终状态（CANCELLED / FILLED 等）；
 * - 任务通常由 Worker 轮询执行（带锁或原子更新）。
 */
public enum JobStatus {

    /**
     * NEW：新建待执行。
     *
     * 含义：
     * - 任务刚入队；
     * - 尚未被 Worker 抢占执行；
     * - next_run_at <= now 时可被调度。
     *
     * 常见来源：
     * - CANCEL_NOT_FOUND
     * - CANCEL_DB_WRITE_FAIL
     * - ORDER_STALE
     */
    NEW,

    /**
     * RUNNING：执行中。
     *
     * 含义：
     * - 任务已被某个 Worker 实例抢占；
     * - 正在执行收敛逻辑；
     * - 应记录 updated_at / worker_id 以防止僵尸任务。
     *
     * 注意：
     * - 若长时间处于 RUNNING，需有超时回收机制；
     * - 防止因进程崩溃导致任务永远卡住。
     */
    RUNNING,

    /**
     * DONE：已完成。
     *
     * 含义：
     * - 收敛逻辑执行成功；
     * - 订单与 RSV 状态已一致；
     * - 无需再次调度。
     *
     * 说明：
     * - 可保留记录用于审计与统计；
     * - 不应再被重复执行。
     */
    DONE,

    /**
     * DEAD：死亡任务（终止）。
     *
     * 含义：
     * - 自动重试次数达到上限；
     * - 或遇到不可恢复异常；
     * - 已升级为人工 Case 或标记不可自动处理。
     *
     * 说明：
     * - DEAD 状态应配合生成 order_reconcile_case；
     * - 不再进入自动重试队列。
     */
    DEAD
}