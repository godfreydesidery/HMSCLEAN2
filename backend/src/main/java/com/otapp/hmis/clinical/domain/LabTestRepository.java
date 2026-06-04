package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link LabTest}.
 *
 * <p>All finders are uid-keyed (String) — no cross-module entity references (ADR-0008 §1,
 * ADR-0022 D2). The worklist finders use the local {@code settled} flag (CR-INC05-01).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Lab worklist: PatientResource.java:3668-3717</li>
 *   <li>Duplicate guard: PatientServiceImpl.java:790-806 (same-type-same-encounter check)</li>
 * </ul>
 */
public interface LabTestRepository extends JpaRepository<LabTest, Long> {

    /**
     * Locate a lab test by ULID public identifier.
     */
    Optional<LabTest> findByUid(String uid);

    /**
     * Find the lab test order whose local bill reference matches {@code patientBillUid}.
     *
     * <p>Used by {@link com.otapp.hmis.clinical.application.ConsultationSettlementListener}
     * to flip {@code LabTest.settled = true} when the billing module publishes a
     * {@code BillSettledEvent}. At most one lab test matches a given bill uid (each lab test
     * order has exactly one bill; ADR-0022 D2).
     *
     * @param patientBillUid the ULID of the PatientBill that was just paid
     * @return the matching lab test, or empty if this bill is not a lab test charge
     */
    Optional<LabTest> findByPatientBillUid(String patientBillUid);

    /**
     * Duplicate guard — consultation path.
     *
     * <p>Returns true if a LabTest already exists for the same consultation and same
     * labTestTypeUid (PatientServiceImpl.java:790-806 parity — reject duplicate orders
     * of the same test type on the same consultation).
     *
     * @param consultation    the owning consultation (intra-module entity ref)
     * @param labTestTypeUid  the lab test type uid to check
     * @return true if a duplicate exists
     */
    boolean existsByConsultationAndLabTestTypeUid(Consultation consultation, String labTestTypeUid);

    /**
     * Duplicate guard — non-consultation (OUTSIDER/walk-in) path.
     *
     * <p>Returns true if a LabTest already exists for the same non-consultation and same
     * labTestTypeUid.
     *
     * @param nonConsultation the owning non-consultation (intra-module entity ref)
     * @param labTestTypeUid  the lab test type uid to check
     * @return true if a duplicate exists
     */
    boolean existsByNonConsultationAndLabTestTypeUid(NonConsultation nonConsultation,
                                                      String labTestTypeUid);

    /**
     * All lab tests for a given consultation, ordered by creation time ascending.
     *
     * @param consultation the owning consultation
     * @return lab tests for this consultation, oldest first
     */
    List<LabTest> findByConsultationOrderByCreatedAtAsc(Consultation consultation);

    /**
     * All lab tests for a given non-consultation, ordered by creation time ascending.
     *
     * @param nonConsultation the owning non-consultation
     * @return lab tests for this non-consultation, oldest first
     */
    List<LabTest> findByNonConsultationOrderByCreatedAtAsc(NonConsultation nonConsultation);

    /**
     * All lab tests for a patient (by uid), ordered by creation time descending.
     *
     * <p>Used by the by-patient query endpoint.
     *
     * @param patientUid the ULID of the patient
     * @return all lab tests for the patient, newest first
     */
    List<LabTest> findByPatientUidOrderByCreatedAtDesc(String patientUid);

    /**
     * Lab department worklist — settled orders in actionable statuses.
     *
     * <p>Returns orders whose {@code settled} flag is true AND whose status is in
     * {PENDING, ACCEPTED, COLLECTED} (i.e., not yet VERIFIED or REJECTED).
     * The settled flag replaces reading the billing bill status (CR-INC05-01, ADR-0022 D4).
     *
     * <p>Legacy citation: PatientResource.java:3668-3717 filtered by bill status PAID|COVERED
     * for outpatient/outsider; this implementation uses the local settled flag instead.
     *
     * @param settled true — only show bills that have been settled/covered
     * @param statuses the actionable status set {PENDING, ACCEPTED, COLLECTED}
     * @return worklist entries, ordered by creation time ascending (oldest first — FIFO queue)
     */
    List<LabTest> findBySettledAndStatusInOrderByCreatedAtAsc(boolean settled,
                                                               List<LabTestStatus> statuses);

    /**
     * Filtered worklist — settled orders with a specific status filter.
     *
     * <p>Used when the caller requests a single-status worklist view
     * (e.g., GET /lab-tests/worklist?status=PENDING).
     *
     * @param settled true — only show settled orders
     * @param status  the specific status to filter on
     * @return matching lab tests, ordered by creation time ascending
     */
    List<LabTest> findBySettledAndStatusOrderByCreatedAtAsc(boolean settled, LabTestStatus status);

    /**
     * By-patient filtered query — all lab tests for a patient with an optional status filter.
     *
     * @param patientUid the ULID of the patient
     * @param status     the specific status to filter on
     * @return matching lab tests for this patient, ordered by creation time descending
     */
    List<LabTest> findByPatientUidAndStatusOrderByCreatedAtDesc(String patientUid,
                                                                 LabTestStatus status);
}
