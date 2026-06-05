package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code PatientNursingCarePlan} record (inc-07 07b,
 * ADR-0008 §1).
 *
 * <p>No entity type leaks across the module boundary.
 *
 * <p>Legacy citation: PatientNursingCarePlan.java:38-41.
 * inc-07 07b / AC-07B-NCP-01.
 *
 * @param uid              the care plan's public ULID
 * @param admissionUid     loose uid of the owning admission
 * @param nurseUid         loose uid of the nurse
 * @param nursingDiagnosis nursing diagnosis (nullable)
 * @param expectedOutcome  expected outcome (nullable)
 * @param implementation   implementation (nullable)
 * @param evaluation       evaluation (nullable)
 * @param createdAt        audit creation instant
 */
public record NursingCarePlanView(
        String uid,
        String admissionUid,
        String nurseUid,
        String nursingDiagnosis,
        String expectedOutcome,
        String implementation,
        String evaluation,
        Instant createdAt
) {
}
