package com.oraclebet.portal.settlement.service;

import com.oraclebet.portal.settlement.entity.ReservationState;

import java.util.UUID;

/**
 * 查 RSV（冻结凭证）当前状态。
 * 数据来源：你们 ledger/position 的 PostgreSQL 表，或者调用内部服务 API 都可以。
 */
public interface ReservationQueryService {

    ReservationState getState(UUID reservationId);

    /** 可选：如果需要，返回剩余冻结金额/数量，用于诊断 */
    default String debugInfo(UUID reservationId) { return null; }
}