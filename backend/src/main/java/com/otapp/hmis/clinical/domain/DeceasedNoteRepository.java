package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link DeceasedNote} (inc-05 C12).
 *
 * <p>All cross-module patient refs are uid-keyed (String — ADR-0008 §1, ADR-0022 D2).
 * Intra-module refs ({@link Consultation}) are direct entity references (permissible — both
 * are in the {@code clinical} module).
 *
 * <p>Legacy citation: PatientResource.java:5826 — load_deceased_list hides ARCHIVED.
 */
public interface DeceasedNoteRepository extends JpaRepository<DeceasedNote, Long> {

    /**
     * Locate a deceased note by ULID public identifier.
     */
    Optional<DeceasedNote> findByUid(String uid);

    /**
     * Find the deceased note for a specific consultation (intra-module FK — OPD path).
     * Used to implement the "reuse if exists" pattern in save_deceased_note.
     *
     * @param consultation the owning consultation
     * @return the note for this consultation, if any
     */
    Optional<DeceasedNote> findByConsultation(Consultation consultation);

    /**
     * Check whether a deceased note already exists for the given consultation.
     * Used by the exactly-one-note guard.
     *
     * @param consultation the owning consultation
     * @return true if a note already exists
     */
    boolean existsByConsultation(Consultation consultation);

    /**
     * Find the deceased note for a specific admission (inpatient path — loose uid, inc-07 07a-3).
     *
     * @param admissionUid the loose uid of the owning admission
     * @return the note for this admission, if any
     */
    Optional<DeceasedNote> findByAdmissionUid(String admissionUid);

    /**
     * Check whether a deceased note already exists for the given admission.
     *
     * @param admissionUid the loose uid of the owning admission
     * @return true if a note already exists
     */
    boolean existsByAdmissionUid(String admissionUid);

    /**
     * List all deceased notes with status in PENDING or APPROVED (ARCHIVED is hidden).
     *
     * <p>Legacy citation: PatientResource.java:5826 (load_deceased_list hides ARCHIVED).
     *
     * @param statuses the set {PENDING, APPROVED}
     * @return all non-archived notes, ordered by creation time descending (newest first)
     */
    List<DeceasedNote> findByStatusInOrderByCreatedAtDesc(List<DeceasedNoteStatus> statuses);
}
