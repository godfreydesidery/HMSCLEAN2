package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code PatientPrescriptionChart} record
 * (inc-07 SEAM-2, ADR-0008 §1).
 *
 * <p>This record is the ONLY representation of a {@code PatientPrescriptionChart} that
 * modules outside {@code clinical} may consume. It carries exactly the fields that the
 * inpatient module needs to display and manage drug-administration chart entries.
 *
 * <p>No entity type leaks across the module boundary. The implementation of the write guards
 * (prescription GIVEN check, exactly-one-encounter, nurse-uid) lives clinical-side in
 * {@code PrescriptionChartPortImpl} and is built in inc-07 chunk 07b.
 *
 * <p>Field set derived from {@code PatientPrescriptionChart} entity
 * (PatientPrescriptionChart.java:34-82):
 * <ul>
 *   <li>{@code dosage} — administered dosage free-text (VARCHAR 200).</li>
 *   <li>{@code output} — observed output free-text (VARCHAR 200).</li>
 *   <li>{@code remark} — remark free-text (TEXT).</li>
 *   <li>{@code nurseUid} — loose uid of the administering nurse.</li>
 *   <li>{@code prescriptionUid} — loose uid of the parent prescription.</li>
 *   <li>{@code createdAt} — audit creation instant from {@code AuditableEntity}.</li>
 * </ul>
 *
 * <p>Legacy citation: PatientPrescriptionChart.java:34-82; PatientServiceImpl.java:2544-2577.
 * inc-07 SEAM-2 / ADR-0008 §1.
 *
 * @param uid             the chart record's public ULID
 * @param prescriptionUid loose uid of the parent prescription
 * @param dosage          administered dosage (free-text; may be null)
 * @param output          observed output (free-text; may be null)
 * @param remark          remark (free-text; may be null)
 * @param nurseUid        loose uid of the administering nurse (may be null for non-inpatient)
 * @param createdAt       audit creation instant
 */
public record PrescriptionChartView(
        String uid,
        String prescriptionUid,
        String dosage,
        String output,
        String remark,
        String nurseUid,
        Instant createdAt
) {
}
