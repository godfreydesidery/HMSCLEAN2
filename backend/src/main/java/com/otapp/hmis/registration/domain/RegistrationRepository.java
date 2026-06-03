package com.otapp.hmis.registration.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Registration}.
 *
 * <p>Only uid-keyed and patient-keyed finders are exposed (ADR-0014 §1 — the hidden
 * {@code id} is never returned from any API or DTO layer).
 */
public interface RegistrationRepository extends JpaRepository<Registration, Long> {

    /**
     * Locate a registration by ULID public identifier.
     */
    Optional<Registration> findByUid(String uid);

    /**
     * Find the registration record for a given patient (OneToOne — at most one result).
     * Used to verify whether a patient is already registered (legacy guard).
     */
    Optional<Registration> findByPatient(Patient patient);
}
