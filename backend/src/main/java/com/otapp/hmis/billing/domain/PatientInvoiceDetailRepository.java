package com.otapp.hmis.billing.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link PatientInvoiceDetail}.
 */
public interface PatientInvoiceDetailRepository extends JpaRepository<PatientInvoiceDetail, Long> {

    Optional<PatientInvoiceDetail> findByUid(String uid);

    /**
     * Find the invoice detail for a specific bill (used by PaymentService to mark PAID
     * and increment invoice.amountPaid — PatientBillResource.java:341-349).
     */
    @Query("""
           SELECT d FROM PatientInvoiceDetail d
           WHERE d.bill.uid = :billUid
           """)
    Optional<PatientInvoiceDetail> findByBillUid(@Param("billUid") String billUid);

    /**
     * Return the parent invoice uid for a given bill uid without lazy-loading the invoice entity.
     * Used in integration tests to assert invoice identity without a surrounding transaction.
     */
    @Query("""
           SELECT d.invoice.uid FROM PatientInvoiceDetail d
           WHERE d.bill.uid = :billUid
           """)
    Optional<String> findInvoiceUidByBillUid(@Param("billUid") String billUid);
}
