package com.otapp.hmis.inpatient.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link DischargePlan} (inc-07 07a-3).
 *
 * <p>Only uid-keyed and admissionUid-keyed finders are exposed (ADR-0014 §1 — internal
 * surrogate {@code id} never serialised). The admissionUid lookup supports the
 * idempotent-save (reuse-if-exists) pattern.
 *
 * <p>Legacy citation: PatientResource.java:5342-5390 — discharge plan save/approve flow.
 */
public interface DischargePlanRepository extends JpaRepository<DischargePlan, Long> {

    /** Locate a discharge plan by its public ULID. */
    Optional<DischargePlan> findByUid(String uid);

    /**
     * Find the discharge plan for a specific admission (idempotent-save lookup).
     *
     * @param admissionUid the loose uid of the owning admission
     * @return the plan for this admission, if any
     */
    Optional<DischargePlan> findByAdmissionUid(String admissionUid);

    /**
     * Check whether a discharge plan already exists for the given admission.
     *
     * @param admissionUid the loose uid of the owning admission
     * @return true if a plan already exists
     */
    boolean existsByAdmissionUid(String admissionUid);
}
