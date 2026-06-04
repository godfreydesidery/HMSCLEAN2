package com.otapp.hmis.clinical.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link ClinicalNote} (inc-05 C5).
 *
 * <p>Finders are keyed by uid or by the intra-module encounter associations.
 * No cross-module entity references (ADR-0008 §1).
 *
 * <p>The partial UNIQUE indexes on {@code consultation_id} and {@code non_consultation_id}
 * (V23) ensure that {@code findByConsultation} and {@code findByNonConsultation} return at
 * most one row per encounter — the UPSERT logic in the service layer depends on this.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>load_clinical_note_by_consultation_id (GET side-effect): PatientResource.java</li>
 *   <li>saveCG UPSERT: PatientResource.java:1469-1598</li>
 * </ul>
 */
public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, Long> {

    /**
     * Locate a clinical note by ULID public identifier.
     */
    Optional<ClinicalNote> findByUid(String uid);

    /**
     * Find the existing clinical note for a consultation (UPSERT lookup).
     *
     * <p>The V23 partial UNIQUE index on {@code consultation_id} guarantees at most one row.
     *
     * @param consultation the consultation entity
     * @return the existing note, or empty if none exists
     */
    Optional<ClinicalNote> findByConsultation(Consultation consultation);

    /**
     * Find the existing clinical note for a non-consultation (UPSERT lookup).
     *
     * <p>The V23 partial UNIQUE index on {@code non_consultation_id} guarantees at most one row.
     *
     * @param nonConsultation the non-consultation entity
     * @return the existing note, or empty if none exists
     */
    Optional<ClinicalNote> findByNonConsultation(NonConsultation nonConsultation);
}
