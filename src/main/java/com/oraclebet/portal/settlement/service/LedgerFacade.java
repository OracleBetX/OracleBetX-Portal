package com.oraclebet.portal.settlement.service;


import java.math.BigDecimal;

public interface LedgerFacade {

    /**
     * 对用户做 CREDIT 入账（结算返还/派奖）
     * 必须幂等：idemKey 重复调用不得重复入账
     */
    void credit(String userId,
                String currency,
                String accountType,
                BigDecimal amount,
                String refKey,
                String idemKey,
                String reason);



    /** 冻结资金，返回 reservationId（写 ledger_reservation + held 增加） */
    String reserve(String userId, String currency, String accountType,
                   BigDecimal amount, String idemKey, String reason);

    /** 提交冻结（扣 balance、减 held，reservation->COMMITTED） */
    void commit(String userId,String reservationId, BigDecimal amount, String idemKey, String reason);

}