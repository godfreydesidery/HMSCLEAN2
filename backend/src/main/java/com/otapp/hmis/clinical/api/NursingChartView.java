package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code PatientNursingChart} record (inc-07 07b,
 * ADR-0008 §1).
 *
 * <p>This record is the ONLY representation of a {@code PatientNursingChart} that modules
 * outside {@code clinical} may consume. No entity type leaks across the module boundary.
 *
 * <p>Legacy citation: PatientNursingChart.java:38-45.
 * inc-07 07b / AC-07B-NCA-01.
 *
 * @param uid              the chart record's public ULID
 * @param admissionUid     loose uid of the owning admission
 * @param nurseUid         loose uid of the nurse
 * @param feeding          feeding observation (nullable)
 * @param changingPosition body-position change observation (nullable)
 * @param bedBathing       bed bathing observation (nullable)
 * @param randomBloodSugar random blood sugar reading (nullable)
 * @param fullBloodSugar   full blood sugar reading (nullable)
 * @param drainageOutput   drainage output (nullable)
 * @param fluidIntake      fluid intake (nullable)
 * @param urineOutput      urine output (nullable)
 * @param createdAt        audit creation instant
 */
public record NursingChartView(
        String uid,
        String admissionUid,
        String nurseUid,
        String feeding,
        String changingPosition,
        String bedBathing,
        String randomBloodSugar,
        String fullBloodSugar,
        String drainageOutput,
        String fluidIntake,
        String urineOutput,
        Instant createdAt
) {
}
