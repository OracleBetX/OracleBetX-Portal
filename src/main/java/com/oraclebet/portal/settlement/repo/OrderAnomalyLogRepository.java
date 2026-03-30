package com.oraclebet.portal.settlement.repo;
import org.springframework.data.jpa.repository.JpaRepository;
import com.oraclebet.portal.settlement.entity.*;

public interface OrderAnomalyLogRepository extends JpaRepository<OrderAnomalyLogEntity, Long> {
}