package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.support.AbstractIntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema smoke test for the inc-05 Clinical/OPD chunk C1 (Flyway V20-V28).
 *
 * <p>Verifies the schema-first deliverable WITHOUT any clinical JPA entities (those arrive in
 * C2..C12). It asserts, against the real Testcontainers Postgres 16:
 * <ol>
 *   <li>All 16 new clinical tables created by V21-V28 exist.</li>
 *   <li>The V20 widened {@code ck_consultations_status} CHECK accepts the full 6-value legacy
 *       vocabulary (including the hyphenated {@code IN-PROCESS}/{@code SIGNED-OUT} and the
 *       single-R {@code TRANSFERED} / single-L {@code CANCELED} spellings) and rejects a value
 *       outside the set (e.g. the rejected planning-doc {@code COMPLETED}).</li>
 *   <li>The new {@code membership_no} + {@code insurance_plan_uid} columns exist on
 *       {@code consultations}.</li>
 *   <li>The one-PENDING-transfer-per-patient partial-unique index exists.</li>
 * </ol>
 *
 * <p>Uses raw JDBC (not repositories) because the clinical entities are not yet mapped in C1.
 * The {@link com.otapp.hmis.clinical.domain.ConsultationStatusConverter} round-trip is
 * exercised separately by the registration ITs (which create PENDING consultations) and is
 * now live in C2 (ADR-0022 — ownership transferred to clinical.domain).
 */
class ClinicalSchemaIT extends AbstractIntegrationTest {

    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** The 16 tables V21-V28 must create (consultations already exists from V19). */
    private static final List<String> NEW_CLINICAL_TABLES = List.of(
            "non_consultations",
            "consultation_transfers",
            "clinical_notes",
            "general_examinations",
            "patient_vitals",
            "working_diagnoses",
            "final_diagnoses",
            "lab_tests",
            "lab_test_attachments",
            "radiologies",
            "radiology_attachments",
            "procedures",
            "prescriptions",
            "prescription_batches",
            "patient_prescription_charts",
            "deceased_notes",
            "referral_plans");

    @Test
    void allNewClinicalTablesExist() {
        for (String table : NEW_CLINICAL_TABLES) {
            Integer count = jdbc().queryForObject(
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table %s must exist (created by V21-V28)", table).isEqualTo(1);
        }
    }

    @Test
    void consultationStatusCheckHasAllSixLegacyValuesAndNoInventions() {
        // Assert the widened CHECK (V20) DEFINITION contains every legacy spelling verbatim —
        // the hyphenated IN-PROCESS/SIGNED-OUT, single-R TRANSFERED, single-L CANCELED — and
        // excludes the rejected planning-doc inventions (COMPLETED/IN_PROGRESS/BOOKED) and the
        // Admission-only STOPPED ghost. (A row-insert assertion is impractical: consultations
        // has many NOT-NULL FK columns; the constraint definition is the authoritative source.)
        String checkDef = jdbc().queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_consultations_status'",
                String.class);
        assertThat(checkDef)
                .contains("PENDING").contains("IN-PROCESS").contains("TRANSFERED")
                .contains("CANCELED").contains("SIGNED-OUT").contains("HELD");
        assertThat(checkDef).doesNotContain("COMPLETED").doesNotContain("IN_PROGRESS")
                .doesNotContain("BOOKED").doesNotContain("STOPPED");
    }

    @Test
    void consultationsHasNewInsuranceColumns() {
        for (String column : List.of("membership_no", "insurance_plan_uid")) {
            Integer count = jdbc().queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'consultations' AND column_name = ?",
                    Integer.class, column);
            assertThat(count).as("consultations.%s must exist (V20)", column).isEqualTo(1);
        }
    }

    @Test
    void onePendingTransferPerPatientPartialUniqueIndexExists() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'consultation_transfers' "
                        + "AND indexname = 'uq_consultation_transfers_one_pending_per_patient'",
                Integer.class);
        assertThat(count).as("the one-PENDING-transfer-per-patient partial unique index must exist")
                .isEqualTo(1);
    }

    @Test
    void prescriptionStatusCheckIsExactlyTwoValues() {
        String checkDef = jdbc().queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_prescriptions_status'",
                String.class);
        // EXACTLY {NOT-GIVEN, GIVEN} — the PENDING..SOLD lifecycle belongs to PharmacySaleOrderDetail.
        assertThat(checkDef).contains("NOT-GIVEN").contains("GIVEN");
        assertThat(checkDef).doesNotContain("SOLD").doesNotContain("ACCEPTED")
                .doesNotContain("VERIFIED").doesNotContain("HELD");
    }

    @Test
    void procedureStatusCheckHasNoApprovedOrCollected() {
        String checkDef = jdbc().queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_procedures_status'",
                String.class);
        assertThat(checkDef).contains("PENDING").contains("ACCEPTED")
                .contains("REJECTED").contains("VERIFIED");
        // Planning-doc M14 APPROVED step is fabricated; procedures never COLLECT.
        assertThat(checkDef).doesNotContain("APPROVED").doesNotContain("COLLECTED");
    }
}
