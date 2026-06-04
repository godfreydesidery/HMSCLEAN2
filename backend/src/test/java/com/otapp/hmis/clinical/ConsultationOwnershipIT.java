package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.support.AbstractIntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema verification for the V29 ownership-transfer migration (ADR-0022 C2).
 *
 * <p>Verifies, against the real Testcontainers Postgres 16, that:
 * <ol>
 *   <li>{@code patient_uid} and {@code visit_uid} columns were added to {@code consultations}.</li>
 *   <li>{@code settled} column was added with type BOOLEAN NOT NULL DEFAULT FALSE.</li>
 *   <li>The legacy {@code patient_id} and {@code visit_id} id-FK columns are DROPPED
 *       (ADR-0022 D2 Correction: a NOT-NULL FK column the entity no longer maps makes every
 *       INSERT fail; the uid columns fully replace them — clinical references patient/visit by
 *       uid only, no cross-module DB FK, per ADR-0008).</li>
 *   <li>The {@code idx_consultations_patient_uid} index exists.</li>
 * </ol>
 *
 * <p>Uses raw JDBC (no JPA) to inspect information_schema — decoupled from entity mapping.
 */
class ConsultationOwnershipIT extends AbstractIntegrationTest {

    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void v29_addsPatientUidColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'consultations' AND column_name = 'patient_uid'",
                Integer.class);
        assertThat(count).as("consultations.patient_uid must exist (V29)").isEqualTo(1);
    }

    @Test
    void v29_addsVisitUidColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'consultations' AND column_name = 'visit_uid'",
                Integer.class);
        assertThat(count).as("consultations.visit_uid must exist (V29)").isEqualTo(1);
    }

    @Test
    void v29_addsSettledColumn() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'consultations' AND column_name = 'settled'",
                Integer.class);
        assertThat(count).as("consultations.settled must exist (V29)").isEqualTo(1);
    }

    @Test
    void v29_dropsPatientIdFkColumn() {
        // ADR-0022 D2 Correction: patient_id (and its FK) are DROPPED — the entity stopped
        // mapping it, so a NOT-NULL patient_id made every INSERT fail. uid replaces it.
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'consultations' AND column_name = 'patient_id'",
                Integer.class);
        assertThat(count).as("consultations.patient_id must be DROPPED (ADR-0022 D2 Correction)").isZero();
    }

    @Test
    void v29_dropsVisitIdFkColumn() {
        // ADR-0022 D2 Correction: visit_id (and its FK) are DROPPED; visit_uid replaces it.
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'consultations' AND column_name = 'visit_id'",
                Integer.class);
        assertThat(count).as("consultations.visit_id must be DROPPED (ADR-0022 D2 Correction)").isZero();
    }

    @Test
    void v29_dropsPatientAndVisitForeignKeys() {
        // The fk_consultations_patient / fk_consultations_visit constraints are gone with the columns.
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conname IN ('fk_consultations_patient', 'fk_consultations_visit')",
                Integer.class);
        assertThat(count).as("the legacy patient/visit FKs must be dropped (ADR-0022 D2 Correction)").isZero();
    }

    @Test
    void v29_patientUidIndexExists() {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'consultations' "
                        + "AND indexname = 'idx_consultations_patient_uid'",
                Integer.class);
        assertThat(count).as("idx_consultations_patient_uid must exist (V29)").isEqualTo(1);
    }

    @Test
    void settledColumnDefaultIsFalse() {
        // Verify the column default is false by inspecting column_default in information_schema
        String columnDefault = jdbc().queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'consultations' AND column_name = 'settled'",
                String.class);
        // PostgreSQL stores boolean defaults as 'false' or 'true'
        assertThat(columnDefault).as("settled column default must be false").contains("false");
    }

    @Test
    void allRequiredColumnsPresent() {
        // The uid-based + settled columns exist; the legacy id-FK columns are gone (Correction).
        List<String> required = List.of(
                "patient_uid", "visit_uid", "settled",     // V29 additions
                "membership_no", "insurance_plan_uid"      // V20 additions
        );
        for (String col : required) {
            Integer count = jdbc().queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'consultations' AND column_name = ?",
                    Integer.class, col);
            assertThat(count).as("consultations.%s must exist", col).isEqualTo(1);
        }
        // The dropped legacy id-FK columns must be absent.
        for (String col : List.of("patient_id", "visit_id")) {
            Integer count = jdbc().queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'consultations' AND column_name = ?",
                    Integer.class, col);
            assertThat(count).as("consultations.%s must be DROPPED", col).isZero();
        }
    }
}
