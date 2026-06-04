package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Radiology}.
 *
 * <p>All finders are uid-keyed (String) — no cross-module entity references (ADR-0008 §1,
 * ADR-0022 D2). The worklist finders use the local {@code settled} flag (CR-INC05-01).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Radiology worklist: PatientResource.java:4280-4292</li>
 *   <li>Duplicate guard: same-type-same-encounter check (mirrors lab pattern)</li>
 * </ul>
 */
public interface RadiologyRepository extends JpaRepository<Radiology, Long> {

    /**
     * Locate a radiology order by ULID public identifier.
     */
    Optional<Radiology> findByUid(String uid);

    /**
     * Find the radiology order whose local bill reference matches {@code patientBillUid}.
     *
     * <p>Used by {@link com.otapp.hmis.clinical.application.ConsultationSettlementListener}
     * to flip {@code Radiology.settled = true} when the billing module publishes a
     * {@code BillSettledEvent}. At most one radiology order matches a given bill uid (each
     * radiology order has exactly one bill; ADR-0022 D2).
     *
     * @param patientBillUid the ULID of the PatientBill that was just paid
     * @return the matching radiology order, or empty if this bill is not a radiology charge
     */
    Optional<Radiology> findByPatientBillUid(String patientBillUid);

    /**
     * Duplicate guard — consultation path.
     *
     * <p>Returns true if a Radiology order already exists for the same consultation and same
     * radiologyTypeUid (mirrors LabTestRepository duplicate guard pattern).
     *
     * @param consultation     the owning consultation (intra-module entity ref)
     * @param radiologyTypeUid the radiology type uid to check
     * @return true if a duplicate exists
     */
    boolean existsByConsultationAndRadiologyTypeUid(Consultation consultation,
                                                     String radiologyTypeUid);

    /**
     * Transfer guard (c) — PatientServiceImpl.java:2778.
     * Returns true if any Radiology order for this consultation is in the given status.
     */
    boolean existsByConsultationAndStatus(Consultation consultation, RadiologyStatus status);

    /**
     * Duplicate guard — non-consultation (OUTSIDER/walk-in) path.
     *
     * @param nonConsultation  the owning non-consultation (intra-module entity ref)
     * @param radiologyTypeUid the radiology type uid to check
     * @return true if a duplicate exists
     */
    boolean existsByNonConsultationAndRadiologyTypeUid(NonConsultation nonConsultation,
                                                        String radiologyTypeUid);

    /**
     * All radiology orders for a given consultation, ordered by creation time ascending.
     *
     * @param consultation the owning consultation
     * @return radiology orders for this consultation, oldest first
     */
    List<Radiology> findByConsultationOrderByCreatedAtAsc(Consultation consultation);

    /**
     * All radiology orders for a given non-consultation, ordered by creation time ascending.
     *
     * @param nonConsultation the owning non-consultation
     * @return radiology orders for this non-consultation, oldest first
     */
    List<Radiology> findByNonConsultationOrderByCreatedAtAsc(NonConsultation nonConsultation);

    /**
     * All radiology orders for a patient (by uid), ordered by creation time descending.
     *
     * <p>Used by the by-patient query endpoint.
     *
     * @param patientUid the ULID of the patient
     * @return all radiology orders for the patient, newest first
     */
    List<Radiology> findByPatientUidOrderByCreatedAtDesc(String patientUid);

    /**
     * Radiology department worklist — settled orders in actionable statuses.
     *
     * <p>Returns orders whose {@code settled} flag is true AND whose status is in
     * {PENDING, ACCEPTED} (not VERIFIED or REJECTED; COLLECTED is a dead state so excluded).
     * The settled flag replaces reading the billing bill status (CR-INC05-01, ADR-0022 D4).
     *
     * <p>Active path: PENDING → ACCEPTED → VERIFIED (no COLLECTED in worklist).
     *
     * @param settled  true — only show bills that have been settled/covered
     * @param statuses the actionable status set {PENDING, ACCEPTED}
     * @return worklist entries, ordered by creation time ascending (oldest first — FIFO queue)
     */
    List<Radiology> findBySettledAndStatusInOrderByCreatedAtAsc(boolean settled,
                                                                  List<RadiologyStatus> statuses);

    /**
     * Filtered worklist — settled orders with a specific status filter.
     *
     * @param settled true — only show settled orders
     * @param status  the specific status to filter on
     * @return matching radiology orders, ordered by creation time ascending
     */
    List<Radiology> findBySettledAndStatusOrderByCreatedAtAsc(boolean settled,
                                                               RadiologyStatus status);

    /**
     * By-patient filtered query — all radiology orders for a patient with an optional status filter.
     *
     * @param patientUid the ULID of the patient
     * @param status     the specific status to filter on
     * @return matching radiology orders for this patient, ordered by creation time descending
     */
    List<Radiology> findByPatientUidAndStatusOrderByCreatedAtDesc(String patientUid,
                                                                   RadiologyStatus status);
}
