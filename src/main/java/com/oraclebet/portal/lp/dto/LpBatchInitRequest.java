package com.oraclebet.portal.lp.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class LpBatchInitRequest {

    private String eventId;
    private BigDecimal initCash;
    private List<MarketInit> items;

    @Data
    public static class MarketInit {
        private String marketId;
        private List<OutcomeInit> outcomes;
    }

    @Data
    public static class OutcomeInit {
        private String selectionId;
        private BigDecimal price;
        private BigDecimal qty;
    }
}
