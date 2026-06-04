package com.otapp.hmis.clinical.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Consultation}.
 *
 * <p>All finders are uid-keyed (String) — no cross-module entity references (ADR-0008 §1,
 * ADR-0022 D2/D6). The legacy {@code existsByPatientAndStatus(Patient, ConsultationStatus)}
 * finders are replaced with {@code patientUid}-based variants.
 *
 * <p>Moved from {@code registration.domain} → {@code clinical.domain} per ADR-0022 D1/D6.
 */
public interface ConsultationRepository extends JpaRepository<Consultation, Long> {

    /**
     * Locate a consultation by ULID public identifier.
     */
    Optional<Consultation> findByUid(String uid);

    /**
     * All consultations for a patient, ordered by creation time descending.
     * Re-keyed from Patient entity to patientUid String (ADR-0022 D6).
     */
    List<Consultation> findByPatientUidOrderByCreatedAtDesc(String patientUid);

    /**
     * Check whether the patient (by uid) has any consultation with the exact supplied status.
     * Re-keyed from Patient entity to patientUid String (ADR-0022 D6).
     *
     * @param patientUid the ULID of the patient
     * @param status     the exact status to test for
     * @return {@code true} if at least one consultation in the given status exists
     */
    boolean existsByPatientUidAndStatus(String patientUid, ConsultationStatus status);

    /**
     * Check whether the patient (by uid) has any consultation in one of the supplied statuses.
     * Re-keyed from Patient entity to patientUid String (ADR-0022 D6).
     *
     * <p>Used by the {@code send-to-doctor} guard (widened status set: PENDING + IN-PROCESS +
     * TRANSFERED) via {@code clinical.api.ConsultationLookup.hasOpenWork}.
     *
     * @param patientUid the ULID of the patient
     * @param statuses   the status set to test membership in
     * @return {@code true} if at least one consultation in any of the given statuses exists
     */
    boolean existsByPatientUidAndStatusIn(String patientUid, Collection<ConsultationStatus> statuses);

    // ---------------------------------------------------------------------------------
    // Doctor worklist finders (PART D — PatientResource.java:817-826)
    // ---------------------------------------------------------------------------------

    /**
     * Pending (non-follow-up, PENDING, settled=true) worklist for a clinician.
     * This is the reception-queue: the patient has been sent and their bill is settled (paid).
     *
     * <p>Parity: legacy PatientResource.java:817-826 filtered the response list by the bill's
     * PAID/COVERED status. Here we use the LOCAL {@code settled} flag instead of reading the
     * billing module (ADR-0022 D4, inc-05 §5).
     *
     * @param clinicianUserUid the ULID of the clinician user
     * @param followUp         should be {@code false} for the standard pending queue
     * @param status           {@link ConsultationStatus#PENDING}
     * @param settled          {@code true} — only show paid/covered consultations
     * @return consultations matching the criteria, ordered by creation time
     */
    List<Consultation> findByClinicianUserUidAndFollowUpAndStatusAndSettledOrderByCreatedAtAsc(
            String clinicianUserUid,
            boolean followUp,
            ConsultationStatus status,
            boolean settled);

    /**
     * In-process worklist for a clinician — consultations the doctor has already opened.
     *
     * @param clinicianUserUid the ULID of the clinician user
     * @param status           {@link ConsultationStatus#IN_PROCESS}
     * @return consultations in IN_PROCESS for this clinician, ordered by creation time
     */
    List<Consultation> findByClinicianUserUidAndStatusOrderByCreatedAtAsc(
            String clinicianUserUid,
            ConsultationStatus status);
}
