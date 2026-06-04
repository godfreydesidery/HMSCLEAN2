package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Prescription}.
 *
 * <p>All finders are uid-keyed (String) — no cross-module entity references (ADR-0008 §1,
 * ADR-0022 D2). The pharmacy worklist finder uses the partial index on
 * {@code status = 'NOT-GIVEN'} (V27). The alert finder supports the C11 drug-alert queries
 * (PatientResource.java:4496, 4556).
 *
 * <p>Duplicate-drug guards use intra-module entity refs (Consultation / NonConsultation)
 * because these are intra-module FKs — the clinical module owns all three tables.
 *
 * <p>CR-INC05-05 corrected behaviour: the non-consultation duplicate check uses a DEDICATED
 * {@code existsByNonConsultationAndMedicineUid} — never a consultation-based check on a
 * possibly-null Optional (which would NPE in the legacy system).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Duplicate guard: PatientServiceImpl.java (same-medicine-same-encounter check)</li>
 *   <li>Alert finder: PatientResource.java:4496, 4556</li>
 *   <li>Pharmacy worklist: PatientResource.java (pharmacy dispense queue)</li>
 * </ul>
 */
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    /**
     * Locate a prescription by ULID public identifier.
     */
    Optional<Prescription> findByUid(String uid);

    // -------------------------------------------------------------------------
    // Duplicate-drug guards (exactly one per encounter type — CR-INC05-05)
    // -------------------------------------------------------------------------

    /**
     * Duplicate guard — consultation path.
     *
     * <p>Returns true if a Prescription already exists for the same consultation and same
     * medicineUid. Called by the service before saving to enforce the hard duplicate-drug block
     * (PatientServiceImpl.java parity: same-medicine-same-consultation → throw).
     *
     * @param consultation the owning consultation (intra-module entity ref)
     * @param medicineUid  the medicine uid to check
     * @return true if a duplicate exists
     */
    boolean existsByConsultationAndMedicineUid(Consultation consultation, String medicineUid);

    /**
     * Duplicate guard — non-consultation (OUTSIDER/walk-in) path.
     *
     * <p>CR-INC05-05 corrected check: uses a dedicated non-consultation query instead of the
     * legacy consultation-based Optional that caused an NPE when the consultation was absent.
     * Returns true if a Prescription already exists for the same non-consultation and same
     * medicineUid.
     *
     * @param nonConsultation the owning non-consultation (intra-module entity ref)
     * @param medicineUid     the medicine uid to check
     * @return true if a duplicate exists
     */
    boolean existsByNonConsultationAndMedicineUid(NonConsultation nonConsultation,
                                                   String medicineUid);

    // -------------------------------------------------------------------------
    // Consultation-scoped list
    // -------------------------------------------------------------------------

    /**
     * All prescriptions for a given consultation, ordered by creation time ascending.
     *
     * @param consultation the owning consultation
     * @return prescriptions for this consultation, oldest first
     */
    List<Prescription> findByConsultationOrderByCreatedAtAsc(Consultation consultation);

    /**
     * All prescriptions for a given non-consultation, ordered by creation time ascending.
     *
     * @param nonConsultation the owning non-consultation
     * @return prescriptions for this non-consultation, oldest first
     */
    List<Prescription> findByNonConsultationOrderByCreatedAtAsc(NonConsultation nonConsultation);

    // -------------------------------------------------------------------------
    // Patient-scoped list
    // -------------------------------------------------------------------------

    /**
     * All prescriptions for a patient (by uid), ordered by creation time descending.
     *
     * @param patientUid the ULID of the patient
     * @return all prescriptions for the patient, newest first
     */
    List<Prescription> findByPatientUidOrderByCreatedAtDesc(String patientUid);

    // -------------------------------------------------------------------------
    // Pharmacy worklist
    // -------------------------------------------------------------------------

    /**
     * Pharmacy dispense queue — all NOT-GIVEN prescriptions (settled or not, so the pharmacist
     * can see pending orders). Ordered by creation time ascending (FIFO queue).
     *
     * <p>Uses the partial index {@code idx_prescriptions_pharmacy_worklist} (V27).
     * The settled filter is intentionally omitted here: the pharmacy sees ALL pending orders
     * (the settled flag gates the lab/radiology worklists; pharmacy queues are different —
     * the pharmacist validates payment before dispensing physically).
     *
     * @return all NOT-GIVEN prescriptions, oldest first
     */
    List<Prescription> findByStatusOrderByCreatedAtAsc(PrescriptionStatus status);

    // -------------------------------------------------------------------------
    // Alert finder (C11 — same-medicine-30-day and unfinished-course)
    // -------------------------------------------------------------------------

    /**
     * Alert finder — all prescriptions for a patient with a specific medicine and status.
     *
     * <p>Used by the C11 drug-alert queries (PatientResource.java:4496 same-medicine-30-day;
     * :4556 unfinished-course). The composite index
     * {@code idx_prescriptions_patient_medicine_status} (V27) drives both queries.
     *
     * @param patientUid  the ULID of the patient
     * @param medicineUid the medicine uid to check
     * @param status      the prescription status to filter on (typically GIVEN)
     * @return matching prescriptions, ordered by creation time descending (newest first)
     */
    List<Prescription> findAllByPatientUidAndMedicineUidAndStatusOrderByCreatedAtDesc(
            String patientUid, String medicineUid, PrescriptionStatus status);

    // -------------------------------------------------------------------------
    // Test-support: controlled approved_at override (C11 IT seeding)
    // -------------------------------------------------------------------------

    /**
     * Override the {@code approved_at} column for a prescription identified by its ULID.
     *
     * <p>Used ONLY by integration tests (C11 {@code PrescribingAlertIT}) to seed GIVEN
     * prescriptions with a controlled dispense timestamp in the past, because
     * {@code Prescription.issue()} always stamps {@code approvedAt = Instant.now()} and
     * there is no public setter on the field (ADR-0014 §1 — immutable after write).
     *
     * <p>The {@code id} field has no public accessor ({@code @Getter(AccessLevel.NONE)}),
     * so the update is keyed on the public {@code uid} instead.
     *
     * @param uid        the ULID of the prescription to update
     * @param approvedAt the target dispense timestamp
     * @return number of rows updated (1 on success, 0 if uid not found)
     */
    @Modifying
    @Query("UPDATE Prescription p SET p.approvedAt = :approvedAt WHERE p.uid = :uid")
    int overrideApprovedAt(@Param("uid") String uid, @Param("approvedAt") java.time.Instant approvedAt);
}
