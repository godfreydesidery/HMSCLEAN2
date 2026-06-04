package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Procedure}.
 *
 * <p>All finders are uid-keyed (String) — no cross-module entity references (ADR-0008 §1,
 * ADR-0022 D2). The worklist finders use the local {@code settled} flag (CR-INC05-01).
 *
 * <p><strong>By-patient omission (CR-INC05-15, DEFERRED):</strong>
 * The legacy {@code get_procedures_by_patient_id} query excludes admission-scoped procedures
 * (those where {@code consultation_id IS NULL AND non_consultation_id IS NULL} — i.e. only the
 * {@code admission_uid} path). In this implementation, {@code findByPatientUidOrderByCreatedAtDesc}
 * returns ALL procedures for the patient regardless of encounter type, since no admission
 * procedures exist in C9. When the admission path is implemented, this query must be filtered to
 * exclude admission-scoped rows per CR-INC05-15.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Procedure worklist:   PatientResource.java (PENDING/ACCEPTED filter)</li>
 *   <li>Duplicate guard:      same-type-same-encounter check (mirrors lab/radiology pattern)</li>
 *   <li>By-patient omission:  CR-INC05-15 DEFER — admission procedures excluded from by-patient</li>
 * </ul>
 */
public interface ProcedureRepository extends JpaRepository<Procedure, Long> {

    /**
     * Locate a procedure order by ULID public identifier.
     */
    Optional<Procedure> findByUid(String uid);

    /**
     * Duplicate guard — consultation path.
     *
     * <p>Returns true if a Procedure order already exists for the same consultation and same
     * procedureTypeUid (mirrors LabTestRepository / RadiologyRepository duplicate guard pattern).
     * Legacy: duplicate procedureType per consultation → 422.
     *
     * @param consultation     the owning consultation (intra-module entity ref)
     * @param procedureTypeUid the procedure type uid to check
     * @return true if a duplicate exists
     */
    boolean existsByConsultationAndProcedureTypeUid(Consultation consultation,
                                                     String procedureTypeUid);

    /**
     * Duplicate guard — non-consultation (OUTSIDER/walk-in) path.
     *
     * @param nonConsultation  the owning non-consultation (intra-module entity ref)
     * @param procedureTypeUid the procedure type uid to check
     * @return true if a duplicate exists
     */
    boolean existsByNonConsultationAndProcedureTypeUid(NonConsultation nonConsultation,
                                                        String procedureTypeUid);

    /**
     * All procedure orders for a given consultation, ordered by creation time ascending.
     *
     * @param consultation the owning consultation
     * @return procedure orders for this consultation, oldest first
     */
    List<Procedure> findByConsultationOrderByCreatedAtAsc(Consultation consultation);

    /**
     * All procedure orders for a given non-consultation, ordered by creation time ascending.
     *
     * @param nonConsultation the owning non-consultation
     * @return procedure orders for this non-consultation, oldest first
     */
    List<Procedure> findByNonConsultationOrderByCreatedAtAsc(NonConsultation nonConsultation);

    /**
     * All procedure orders for a patient (by uid), ordered by creation time descending.
     *
     * <p><strong>DEFERRED (CR-INC05-15):</strong> The legacy get_procedures_by_patient_id
     * excludes admission-scoped procedures. This finder currently returns ALL procedures for
     * the patient (admission path not yet implemented). When the admission path lands, this
     * must be restricted to consultation + non_consultation procedures only.
     *
     * @param patientUid the ULID of the patient
     * @return all procedure orders for the patient, newest first
     */
    List<Procedure> findByPatientUidOrderByCreatedAtDesc(String patientUid);

    /**
     * Procedure worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     *
     * <p>Outpatient + outsider only (no inpatient procedure list in the legacy system).
     * Returns orders whose {@code settled} flag is true AND whose status is in
     * {PENDING, ACCEPTED}. The settled flag replaces reading the billing bill status
     * (CR-INC05-01, ADR-0022 D4).
     *
     * <p>NO inpatient procedures in the legacy worklist — reproduced verbatim.
     *
     * @param settled  true — only show settled/covered orders
     * @param statuses the actionable status set {PENDING, ACCEPTED}
     * @return worklist entries, ordered by creation time ascending (oldest first — FIFO queue)
     */
    List<Procedure> findBySettledAndStatusInOrderByCreatedAtAsc(boolean settled,
                                                                  List<ProcedureStatus> statuses);

    /**
     * Filtered worklist — settled orders with a specific status filter.
     *
     * @param settled true — only show settled orders
     * @param status  the specific status to filter on
     * @return matching procedure orders, ordered by creation time ascending
     */
    List<Procedure> findBySettledAndStatusOrderByCreatedAtAsc(boolean settled,
                                                               ProcedureStatus status);

    /**
     * By-patient filtered query — all procedure orders for a patient with a status filter.
     *
     * @param patientUid the ULID of the patient
     * @param status     the specific status to filter on
     * @return matching procedure orders for this patient, ordered by creation time descending
     */
    List<Procedure> findByPatientUidAndStatusOrderByCreatedAtDesc(String patientUid,
                                                                    ProcedureStatus status);
}
