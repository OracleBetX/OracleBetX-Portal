package com.oraclebet.portal.settlement.repo;

import com.oraclebet.portal.settlement.entity.EventSettlementItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventSettlementItemRepository extends JpaRepository<EventSettlementItemEntity, String> {

    boolean existsByBatchIdAndUserIdAndSelectionId(String batchId, String userId, String selectionId);

    List<EventSettlementItemEntity> findByBatchId(String batchId);
}