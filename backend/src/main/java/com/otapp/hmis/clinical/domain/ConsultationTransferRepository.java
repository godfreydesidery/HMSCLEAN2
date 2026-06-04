package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link ConsultationTransfer}.
 *
 * <p>All finders are uid-keyed or status-keyed (String / enum) — no cross-module entity refs
 * (ADR-0008 §1, ADR-0022 D2 Correction).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>existsByPatientUidAndStatus(patientUid, PENDING): PatientServiceImpl.java:2764-2767
 *       (at-most-one-PENDING guard before raise)</li>
 *   <li>findByPatientUidAndStatus(patientUid, PENDING): PatientServiceImpl.java:431
 *       (completion seam — doConsultation pending-transfer lookup)</li>
 *   <li>findAllByStatus(PENDING): PatientResource.java:599
 *       (get_consultation_transfers — system-wide unscoped queue)</li>
 * </ul>
 */
public interface ConsultationTransferRepository extends JpaRepository<ConsultationTransfer, Long> {

    /**
     * Locate a transfer by ULID public identifier.
     */
    Optional<ConsultationTransfer> findByUid(String uid);

    /**
     * Find the (at most one) transfer for a consultation in the given status.
     *
     * <p>Used by raise/cancel to locate the PENDING transfer associated with a source
     * consultation (PatientServiceImpl.java:2764, :2814).
     *
     * @param consultation the source consultation entity (intra-module FK — legal)
     * @param status       the transfer status to filter on
     * @return the matching transfer, or empty if none
     */
    Optional<ConsultationTransfer> findByConsultationAndStatus(
            Consultation consultation, ConsultationTransferStatus status);

    /**
     * Check whether the patient (by uid) already has a PENDING transfer.
     *
     * <p>Guard (b) in raise: at most one PENDING transfer per patient
     * (PatientServiceImpl.java:2764-2767). The partial-unique index
     * {@code uq_consultation_transfers_one_pending_per_patient} backstops this.
     *
     * @param patientUid loose uid of the patient
     * @param status     the transfer status (expected: PENDING)
     * @return {@code true} if a transfer in the given status exists for this patient
     */
    boolean existsByPatientUidAndStatus(String patientUid, ConsultationTransferStatus status);

    /**
     * Find the (at most one) PENDING transfer for a patient, if any.
     *
     * <p>Used by the completion seam in ConsultationBookingServiceImpl.book:
     * when a booking arrives, check whether a PENDING transfer exists for the patient
     * and whether the target clinic matches the transfer's destination
     * (PatientServiceImpl.java:431-435).
     *
     * @param patientUid loose uid of the patient
     * @param status     the transfer status (expected: PENDING)
     * @return the matching transfer, or empty if none
     */
    Optional<ConsultationTransfer> findByPatientUidAndStatus(
            String patientUid, ConsultationTransferStatus status);

    /**
     * System-wide pending-transfer queue — all PENDING transfers, unscoped
     * (PatientResource.java:599 — {@code findAllByStatus("PENDING")}, no patient/clinic filter).
     *
     * <p>This is the reception/triage queue that shows all outstanding hand-off requests.
     *
     * @param status the transfer status to filter on (expected: PENDING)
     * @return all transfers in the given status, in insertion order
     */
    List<ConsultationTransfer> findAllByStatus(ConsultationTransferStatus status);
}
