package com.oraclebet.portal.lp.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class LpInitRequest {
    private String lpUserId;
    private String eventId;
    private String marketId;

    private String homeSelectionId;
    private BigDecimal homePrice;
    private BigDecimal homeQty;

    private String awaySelectionId;
    private BigDecimal awayPrice;
    private BigDecimal awayQty;

    /** 可选：先 credit 注资金额，null/0 则不注资 */
    private BigDecimal initCash;

    /**
     * 强制重做：true 时即使已有 DONE/FAILED/INITING 记录也重置后重新执行。
     * 依赖 LedgerFacade 与 AE init-inventory 的 idemKey 幂等性，重做不会重复扣款或建仓。
     */
    private boolean force;
}
