package com.otapp.hmis.billing.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link PatientInvoice}.
 */
public interface PatientInvoiceRepository extends JpaRepository<PatientInvoice, Long> {

    Optional<PatientInvoice> findByUid(String uid);

    /**
     * Find invoices for a patient, optionally filtered by status.
     * Used for the cashier invoice queue (GET /billing/invoices?patientUid&status).
     */
    List<PatientInvoice> findByPatientUidAndStatus(String patientUid, InvoiceStatus status);

    /**
     * Find invoices for a patient (all statuses).
     */
    List<PatientInvoice> findByPatientUid(String patientUid);

    /**
     * Find the single PENDING insurance invoice for a (patient, plan) pair.
     * The PENDING-invoice accumulator pattern: one per (patient+plan).
     * PatientServiceImpl.java:342, :631, :871 (creation pattern).
     */
    @Query("""
           SELECT i FROM PatientInvoice i
           WHERE i.patientUid = :patientUid
             AND i.planUid = :planUid
             AND i.status = 'PENDING'
           """)
    Optional<PatientInvoice> findPendingInsuranceInvoice(
            @Param("patientUid") String patientUid,
            @Param("planUid") String planUid);

    /**
     * Find the single PENDING cash invoice for a patient (planUid IS NULL).
     * Used for attaching VERIFIED inpatient fallback bills.
     */
    @Query("""
           SELECT i FROM PatientInvoice i
           WHERE i.patientUid = :patientUid
             AND i.planUid IS NULL
             AND i.status = 'PENDING'
           """)
    Optional<PatientInvoice> findPendingCashInvoice(@Param("patientUid") String patientUid);

    /**
     * Approve all PENDING invoices for a patient (batch-close at start of new charge tx).
     * PatientServiceImpl.java:586-590.
     */
    @Query("""
           SELECT i FROM PatientInvoice i
           WHERE i.patientUid = :patientUid
             AND i.status = 'PENDING'
           """)
    List<PatientInvoice> findAllPendingForPatient(@Param("patientUid") String patientUid);
}
