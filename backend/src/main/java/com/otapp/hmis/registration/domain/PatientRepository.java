package com.otapp.hmis.registration.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Paginated patient search (build-spec §6, CR-07; REG-1 closed). Case-insensitive
     * {@code LIKE %q%} OR-match across {@code no}, names, {@code phoneNo}, AND {@code membershipNo}
     * (the membership-card lookup) — mirrors legacy PatientRepository.java:41-42,:50-51, unified
     * into one query. Backed by the GIN trigram index on {@code search_key} + per-field indexes.
     * Nullable fields (middleName/phoneNo/membershipNo) simply don't match when null.
     *
     * @param q        the case-insensitive substring to match (empty matches all)
     * @param pageable page request
     * @return a page of matching patients
     */
    @Query("""
           SELECT p FROM Patient p
           WHERE LOWER(p.no)           LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.firstName)    LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.middleName)   LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.lastName)     LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.phoneNo)      LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.membershipNo) LIKE LOWER(CONCAT('%', :q, '%'))
           """)
    Page<Patient> search(@Param("q") String q, Pageable pageable);
}
