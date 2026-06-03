package com.otapp.hmis.registration.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link Patient}.
 *
 * <p>Only uid-keyed and no-keyed finders are exposed (ADR-0014 §1 — the hidden {@code id}
 * is never returned from any API or DTO layer).
 *
 * <p>Pagination and search finders are added in C5 (build-spec §8).
 */
public interface PatientRepository extends JpaRepository<Patient, Long> {

    /**
     * Locate a patient by ULID public identifier.
     * Primary lookup for all application-layer operations (ADR-0014 §1).
     */
    Optional<Patient> findByUid(String uid);

    /**
     * Locate a patient by Medical Record Number (MRN / {@code no}).
     * Used by legacy-parity search and walk-in patient lookup
     * (PatientServiceImpl.java:3266, :3398).
     */
    Optional<Patient> findByNo(String no);

    /**
     * Advance {@code seq_mrno} (V13__masterdata_document_sequences.sql:57-58) and return
     * the raw BIGINT for MRN construction.
     *
     * <p>The MRN format is {@code MRNO/{EAT-year}/{seq}} (build-spec §2.1, CR-02).
     * The {@code MrNumberGenerator} in C2 calls this and formats the final string.
     * Using a DB sequence is atomic — this is the ratified fix for the legacy
     * {@code MAX(id)+1} race condition (ADR-0009 §5).
     */
    @Query(nativeQuery = true, value = "SELECT nextval('seq_mrno')")
    Long nextMrNo();
}
