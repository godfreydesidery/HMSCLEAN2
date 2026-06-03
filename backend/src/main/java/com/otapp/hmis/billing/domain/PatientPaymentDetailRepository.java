package com.otapp.hmis.billing.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link PatientPaymentDetail}.
 */
public interface PatientPaymentDetailRepository extends JpaRepository<PatientPaymentDetail, Long> {

    Optional<PatientPaymentDetail> findByUid(String uid);

    /**
     * Find the payment detail for a specific bill uid.
     * Used by credit-note / cancel service to flip status to REFUNDED (PatientResource.java:636-639).
     */
    @Query("""
           SELECT d FROM PatientPaymentDetail d
           WHERE d.bill.uid = :billUid
             AND d.status = 'RECEIVED'
           """)
    Optional<PatientPaymentDetail> findReceivedByBillUid(@Param("billUid") String billUid);
}
