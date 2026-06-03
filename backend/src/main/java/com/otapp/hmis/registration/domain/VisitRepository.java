package com.otapp.hmis.registration.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Visit}.
 *
 * <p>Only uid-keyed and patient-keyed finders are exposed (ADR-0014 §1 — the hidden
 * {@code id} is never returned from any API or DTO layer).
 *
 * <p>The "last visit" query (build-spec §6, CR-08) uses
 * {@link #findFirstByPatientOrderByCreatedAtDesc(Patient)} which is backed by
 * {@code idx_visits_patient_created_at (patient_id, created_at DESC)} in V19.
 */
public interface VisitRepository extends JpaRepository<Visit, Long> {

    /**
     * Locate a visit by ULID public identifier.
     */
    Optional<Visit> findByUid(String uid);

    /**
     * All visits for a patient, ordered by creation time descending (most recent first).
     * Backed by {@code idx_visits_patient_created_at}.
     * PatientResource.java:788-799.
     */
    List<Visit> findByPatientOrderByCreatedAtDesc(Patient patient);

    /**
     * The most recent visit for a patient (explicit ORDER BY created_at DESC LIMIT 1 — CR-08).
     * Backed by {@code idx_visits_patient_created_at}.
     * Safer than the legacy last-element-of-unordered-list pattern
     * (PatientResource.java:795-799).
     */
    Optional<Visit> findFirstByPatientOrderByCreatedAtDesc(Patient patient);
}
