package com.otapp.hmis.billing.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientBill}.
 *
 * <p>Only uid-keyed finders are exposed (ADR-0014 §1 — id is never exposed externally).
 * Invoice detail lookups are on {@link PatientInvoiceDetailRepository#findByBillUid}.
 */
public interface PatientBillRepository extends JpaRepository<PatientBill, Long> {

    Optional<PatientBill> findByUid(String uid);

    /**
     * Find all bills for a patient by status (cashier collection queue).
     * PatientBillResource.java:415+ (UNPAID/VERIFIED filter).
     */
    List<PatientBill> findByPatientUidAndStatusIn(String patientUid, List<BillStatus> statuses);

    /**
     * Find all bills for a patient (all statuses — for invoice/receipt listing).
     */
    List<PatientBill> findByPatientUid(String patientUid);
}
