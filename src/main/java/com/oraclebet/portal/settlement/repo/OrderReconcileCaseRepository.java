package com.oraclebet.portal.settlement.repo;

import com.oraclebet.portal.settlement.entity.CaseStatus;
import com.oraclebet.portal.settlement.entity.OrderReconcileCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderReconcileCaseRepository extends JpaRepository<OrderReconcileCaseEntity, Long> {

    @Query("select c from OrderReconcileCaseEntity c where c.orderId = :orderId and c.caseStatus = :caseStatus")
    Optional<OrderReconcileCaseEntity> findByOrderIdAndCaseStatus(@Param("orderId") UUID orderId,
                                                                   @Param("caseStatus") CaseStatus caseStatus);

    default Optional<OrderReconcileCaseEntity> findOpenByOrderId(UUID orderId) {
        return findByOrderIdAndCaseStatus(orderId, CaseStatus.OPEN);
    }
}
