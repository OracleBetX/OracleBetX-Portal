package com.oraclebet.portal.lp.dto;

import lombok.Data;
import java.util.List;

@Data
public class LpBatchInitResponse {

    private boolean ok;
    private String eventId;
    private int totalMarkets;
    private int totalOutcomes;
    private int successCount;
    private int failedCount;
    private List<OutcomeResult> results;

    @Data
    public static class OutcomeResult {
        private String marketId;
        private String selectionId;
        private String userId;
        private String email;
        private String status;
        private String message;
    }
}
