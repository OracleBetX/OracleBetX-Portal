package com.oraclebet.portal.settlement.repo;

import com.oraclebet.portal.settlement.entity.EventSettlementBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSettlementBatchRepository extends JpaRepository<EventSettlementBatchEntity, String> {
}