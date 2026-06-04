package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link ReferralPlan} (inc-05 C12).
 *
 * <p>All cross-module patient refs are uid-keyed (String — ADR-0008 §1, ADR-0022 D2).
 * Intra-module refs ({@link Consultation}) are direct entity references.
 *
 * <p>Legacy citation: PatientResource.java (load_referral_list hides ARCHIVED; mirrors
 * the deceased list pattern).
 */
public interface ReferralPlanRepository extends JpaRepository<ReferralPlan, Long> {

    /**
     * Locate a referral plan by ULID public identifier.
     */
    Optional<ReferralPlan> findByUid(String uid);

    /**
     * Find the referral plan for a specific consultation (intra-module FK — OPD path).
     * Used to check for an existing plan before creating a new one.
     *
     * @param consultation the owning consultation
     * @return the plan for this consultation, if any
     */
    Optional<ReferralPlan> findByConsultation(Consultation consultation);

    /**
     * Check whether a PENDING referral plan already exists for the given consultation.
     *
     * <p>The save_referral_plan guard: a PENDING plan already exists → throw
     * "A pending referral plan already exists for this consultation" (422).
     *
     * @param consultation the owning consultation
     * @param status       {@link ReferralPlanStatus#PENDING}
     * @return true if a PENDING plan already exists
     */
    boolean existsByConsultationAndStatus(Consultation consultation, ReferralPlanStatus status);

    /**
     * List all referral plans with status in PENDING or APPROVED (ARCHIVED is hidden).
     *
     * <p>Mirrors the deceased list pattern (PatientResource.java:5826 — ARCHIVED hidden).
     *
     * @param statuses the set {PENDING, APPROVED}
     * @return all non-archived plans, ordered by creation time descending (newest first)
     */
    List<ReferralPlan> findByStatusInOrderByCreatedAtDesc(List<ReferralPlanStatus> statuses);
}
