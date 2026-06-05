package com.otapp.hmis.billing.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Find all bill uids linked to the given admission.
     *
     * <p>Used by {@link com.otapp.hmis.billing.api.BillingCommands#approveInvoicesForAdmission}
     * to collect all bills for an admission so their parent invoices can be approved.
     * PatientResource.java:5354-5357 (discharge), :5626-5631 (referral), :5884-5887 (deceased).
     *
     * @param admissionUid the loose uid of the admission
     * @return list of all PatientBill uids linked to this admission
     */
    @Query("SELECT b.uid FROM PatientBill b WHERE b.admissionUid = :admissionUid")
    List<String> findUidsByAdmissionUid(@Param("admissionUid") String admissionUid);

    /**
     * Test whether any bill linked to the given admission is in one of the supplied statuses.
     *
     * <p>Used by {@link com.otapp.hmis.billing.api.BillingQueries#admissionHasOutstandingBills}
     * to implement the discharge bills-cleared gate
     * (PatientResource.java:5342-5357, :5593-5603, :5851-5882). Populated once chunk 07a adds
     * {@code PatientBill.admissionUid} and {@code BillingCommandsImpl} sets it at ward-charge
     * creation time (inc-07 07a).
     *
     * @param admissionUid the loose uid of the admission to check
     * @param statuses     the set of bill statuses to match (typically UNPAID + VERIFIED)
     * @return {@code true} if at least one matching bill exists
     */
    boolean existsByAdmissionUidAndStatusIn(String admissionUid, List<BillStatus> statuses);
}
