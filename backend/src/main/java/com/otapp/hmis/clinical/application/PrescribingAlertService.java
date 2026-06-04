package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.PrescriptionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advisory prescribing-alert computation (inc-05 C11).
 *
 * <p>Implements EXACTLY TWO soft/advisory alerts for the prescription CREATE response.
 * Neither alert ever blocks the save; both are returned in
 * {@link com.otapp.hmis.clinical.application.dto.PrescriptionDto#alerts()}.
 *
 * <p><strong>Alert 1 — DUPLICATE_MEDICINE (same-medicine-within-30-days):</strong>
 * (PatientResource.java:4480-4521)
 * <ol>
 *   <li>Load all prior GIVEN prescriptions for the same patient + same medicine.</li>
 *   <li>Select the one with MAX(approvedAt) — CR-INC05-12 deterministic selection
 *       (legacy used the last of an unordered list; same business intent, deterministic).</li>
 *   <li>Compute elapsed days using {@code ChronoUnit.DAYS.between(approvedAt, now)}.</li>
 *   <li>If {@code days > 0} and {@code days <= 30} → append "Has Drugs this month."</li>
 *   <li>If {@code days == 0} (dispensed earlier today) → also append "Has Drugs this month."</li>
 *   <li>If no prior GIVEN prescription → no alert.</li>
 * </ol>
 *
 * <p><strong>Alert 2 — UNFINISHED_COURSE (unfinished-course):</strong>
 * (PatientResource.java:4528-4580)
 * <ol>
 *   <li>Load all prior GIVEN prescriptions for the same patient + same medicine.</li>
 *   <li>Select the one with MAX(approvedAt) — CR-INC05-12 deterministic selection.</li>
 *   <li>Parse the prescription's {@code days} String → int. A non-numeric value (e.g.
 *       "as directed") causes a {@code NumberFormatException} which is SWALLOWED → no alert
 *       (exact legacy behaviour: PatientResource.java:4556-4558).</li>
 *   <li>Compute elapsed days using {@code ChronoUnit.DAYS.between(approvedAt, now)}.</li>
 *   <li>If {@code elapsed < prescribedDays} → append the unfinished-course alert text.</li>
 *   <li>The entire computation is wrapped in a try/catch that swallows ALL exceptions
 *       to produce no-alert (PatientResource.java:4537, 4573 — the outer try/catch pattern).</li>
 * </ol>
 *
 * <p><strong>Why the new NOT-GIVEN prescription does NOT self-trigger:</strong>
 * Both alerts filter on {@code status = GIVEN}. A freshly saved prescription has status
 * {@code NOT_GIVEN}; it is therefore naturally excluded by the repository finder without
 * any additional ordering dependency. Alert computation may safely happen either before
 * or after the save of the new prescription.
 *
 * <p><strong>Determinism via MAX(approvedAt) (CR-INC05-12):</strong>
 * The repository finder returns rows ordered by {@code created_at DESC} (an approximation).
 * We then pick the one with the highest {@code approvedAt} in Java, which is the true
 * dispense timestamp. This is deterministic and matches the business intent of "the most
 * recently dispensed course of this medicine".
 *
 * <p><strong>Clock discipline:</strong>
 * The "now" instant is threaded in via {@code TxAuditContext.timestamp()} — NOT via
 * {@code Instant.now()} or {@code LocalDate.now()} — making alert computation deterministic
 * and testable without clock mocking.
 *
 * <p>Package-private. Consumed only by {@link PrescriptionService} within
 * {@code clinical.application}. Pure read service — no side effects.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Alert 1 (DUPLICATE_MEDICINE): PatientResource.java:4480-4521</li>
 *   <li>Alert 2 (UNFINISHED_COURSE): PatientResource.java:4528-4580</li>
 *   <li>Deterministic MAX(approvedAt): CR-INC05-12 (11-DECISIONS-RATIFIED.md §2)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class PrescribingAlertService {

    // Alert message strings — EXACT legacy text (PatientResource.java:4509, 4568).
    // Alert 1: legacy emits " Has Drugs this month. " (with surrounding spaces) as part of a
    // concatenation on obj.getValue(). We emit the core advisory text without the leading/trailing
    // whitespace that was an artifact of the concatenation context; the field holds the alert in
    // isolation. Text reproduced: "Has Drugs this month."
    static final String ALERT_HAS_DRUGS_THIS_MONTH = "Has Drugs this month.";

    // Alert 2 message prefix matches PatientResource.java:4568.
    // Full text example: "The patient has not completed the last prescription. There are X days
    // left to finish this medicine. Was prescribed on <dateTime> for <days> days"
    // We produce the message programmatically using the same format (see buildUnfinishedMessage).
    static final String ALERT_UNFINISHED_PREFIX =
            "The patient has not completed the last prescription.";

    private final PrescriptionRepository prescriptionRepository;

    /**
     * Compute advisory alerts for a prescription being created.
     *
     * <p>Both alerts are SOFT (advisory-only). Neither blocks the prescription save.
     * Returns an empty list if neither condition fires.
     *
     * @param patientUid  ULID of the patient
     * @param medicineUid ULID of the medicine being prescribed
     * @param now         the logical operation timestamp (from {@code TxAuditContext.timestamp()})
     * @return list of advisory alert strings (never null; may be empty)
     */
    @Transactional(readOnly = true)
    List<String> computeAlerts(String patientUid, String medicineUid, Instant now) {
        List<String> alerts = new ArrayList<>();

        // Load all prior GIVEN prescriptions for this patient + medicine.
        // Repository returns them ordered by created_at DESC (a proxy for recency).
        // We then select MAX(approvedAt) in Java for determinism (CR-INC05-12).
        List<Prescription> priorGiven =
                prescriptionRepository.findAllByPatientUidAndMedicineUidAndStatusOrderByCreatedAtDesc(
                        patientUid, medicineUid, PrescriptionStatus.GIVEN);

        if (priorGiven.isEmpty()) {
            // Brand-new patient/medicine combination — no alerts possible
            return List.of();
        }

        // Select the prescription with the MAX approvedAt (CR-INC05-12 deterministic choice).
        // approvedAt is the dispense timestamp written by Prescription.issue().
        // It is non-null on all GIVEN prescriptions (the GIVEN state is only reached via issue()).
        Optional<Prescription> latestOpt = priorGiven.stream()
                .filter(p -> p.getApprovedAt() != null)
                .max(Comparator.comparing(Prescription::getApprovedAt));

        if (latestOpt.isEmpty()) {
            // No GIVEN prescription has an approvedAt — defensive guard (should not happen
            // in normal operation, but treat same as "no prior given prescription").
            return List.of();
        }

        Prescription latest = latestOpt.get();

        // =====================================================================
        // Alert 1: DUPLICATE_MEDICINE — same medicine within 30 days
        // (PatientResource.java:4480-4521)
        // =====================================================================
        computeSameMedicineAlert(latest.getApprovedAt(), now, alerts);

        // =====================================================================
        // Alert 2: UNFINISHED_COURSE — patient has not finished their last course
        // (PatientResource.java:4528-4580)
        // =====================================================================
        computeUnfinishedCourseAlert(latest, now, alerts);

        return List.copyOf(alerts);
    }

    /**
     * Alert 1 — DUPLICATE_MEDICINE computation (PatientResource.java:4498-4516).
     *
     * <p>Logic:
     * <ul>
     *   <li>Compute {@code days = ChronoUnit.DAYS.between(approvedAt, now)}.</li>
     *   <li>If {@code days > 0} and {@code days <= 30} → fire alert.</li>
     *   <li>If {@code days == 0} (same day — legacy used hours branch) → also fire alert.</li>
     * </ul>
     *
     * <p>The legacy code branched on {@code days > 0} vs. the hours-based same-day path.
     * In both branches the "Has Drugs this month." alert was emitted. We unify: if
     * {@code days <= 30} (which includes 0), emit the alert. This is equivalent to the legacy
     * two-branch emission and is simpler to test.
     *
     * @param approvedAt the dispense timestamp of the latest prior GIVEN prescription
     * @param now        the logical operation timestamp
     * @param alerts     mutable list to append to if the condition fires
     */
    private static void computeSameMedicineAlert(Instant approvedAt, Instant now,
                                                  List<String> alerts) {
        long days = ChronoUnit.DAYS.between(approvedAt, now);
        // Legacy: days > 0 branch → days <= 30; days == 0 branch (hours path) → always fires.
        // Unified: days <= 30 covers both branches (days=0 is <= 30, days=1..30 also fires).
        // Negative days (approvedAt in the future relative to now) → no alert (correct: if
        // somehow approvedAt > now, days < 0, so the condition days <= 30 would still be true —
        // but a future approvedAt is a data anomaly we guard against by requiring days >= 0.
        if (days >= 0 && days <= 30) {
            alerts.add(ALERT_HAS_DRUGS_THIS_MONTH);
        }
    }

    /**
     * Alert 2 — UNFINISHED_COURSE computation (PatientResource.java:4537-4575).
     *
     * <p>The entire computation is wrapped in a try/catch that SWALLOWS all exceptions
     * to produce no-alert — exactly as in the legacy outer try/catch block
     * (PatientResource.java:4537 / 4573). A non-numeric {@code days} field (e.g.
     * "as directed") causes a {@link NumberFormatException} at the
     * {@link Integer#valueOf(String)} call which is caught and silently ignored.
     *
     * @param latest the prior GIVEN prescription with the latest approvedAt
     * @param now    the logical operation timestamp
     * @param alerts mutable list to append to if the condition fires
     */
    private static void computeUnfinishedCourseAlert(Prescription latest, Instant now,
                                                      List<String> alerts) {
        try {
            // Parse days from numeric-as-string field (PatientResource.java:4556).
            // NumberFormatException → caught below → no alert (legacy behaviour).
            int prescribedDays = Integer.valueOf(latest.getDays());

            // Elapsed days since dispense (PatientResource.java:4561).
            double elapsed = ChronoUnit.DAYS.between(latest.getApprovedAt(), now);

            // Fire if the patient has not yet completed the course (PatientResource.java:4567).
            if (elapsed < prescribedDays) {
                double daysLeft = prescribedDays - elapsed;
                String message = "The patient has not completed the last prescription. "
                        + "There are " + Double.toString(daysLeft)
                        + " days left to finish this medicine. "
                        + "Was prescribed on " + latest.getApprovedAt().toString()
                        + " for " + latest.getDays() + " days";
                alerts.add(message);
            }
        } catch (Exception ex) {
            // Swallow ALL exceptions — exact legacy outer try/catch pattern
            // (PatientResource.java:4573). A non-numeric days value (e.g. "as directed")
            // causes a NumberFormatException here; we discard it and produce no alert.
        }
    }
}
