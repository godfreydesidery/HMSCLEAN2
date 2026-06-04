package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PrescriptionBatch}.
 *
 * <p>Near-inert — prescription batches are traceability records with no consuming logic.
 * Provides the minimal surface needed for the add/list endpoints.
 *
 * <p>Legacy citation: PrescriptionBatch.java:34-48.
 */
public interface PrescriptionBatchRepository extends JpaRepository<PrescriptionBatch, Long> {

    /**
     * Locate a prescription batch by ULID public identifier.
     */
    Optional<PrescriptionBatch> findByUid(String uid);

    /**
     * All batches for a given prescription, ordered by creation time ascending.
     *
     * @param prescription the parent prescription
     * @return batches for this prescription, oldest first
     */
    List<PrescriptionBatch> findByPrescriptionOrderByCreatedAtAsc(Prescription prescription);
}
