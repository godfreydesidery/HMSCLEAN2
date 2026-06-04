package com.otapp.hmis.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.registration.domain.Registration;
import com.otapp.hmis.registration.domain.RegistrationRepository;
import com.otapp.hmis.registration.domain.RegistrationStatus;
import com.otapp.hmis.registration.domain.Visit;
import com.otapp.hmis.registration.domain.VisitRepository;
import com.otapp.hmis.registration.domain.VisitSequence;
import com.otapp.hmis.registration.domain.VisitStatus;
import com.otapp.hmis.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence smoke test for the inc-03 C1 schema and domain layer (build-spec §8 C1).
 *
 * <p>Updated in inc-05 C2 (ADR-0022 D6): {@code Consultation} is now
 * {@code clinical.domain.Consultation}; its constructor takes loose ULID strings instead of
 * entity references. The repository is {@code clinical.domain.ConsultationRepository}.
 * The persistence assertions are mechanically updated; the observable behaviour is identical.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Patient + Registration + FIRST Visit + PENDING Consultation persist and reload
 *       correctly via the repositories.</li>
 *   <li>UIDs are 26-character ULIDs assigned at {@code @PrePersist}.</li>
 *   <li>The hidden {@code id} field is NOT reachable from the public API of any entity
 *       ({@code getId()} must not compile — verified by the fact that no such call exists
 *       here and ArchUnit enforces it globally).</li>
 *   <li>Column mappings satisfy {@code ddl-auto=validate} (Hibernate validates against
 *       V19+V29 on context startup — if this test runs, the schema is consistent).</li>
 * </ol>
 *
 * <p>Cross-module loose uids ({@code insurancePlanUid}, {@code patientBillUid},
 * {@code clinicUid}, {@code clinicianUserUid}, {@code businessDayUid}) use placeholder
 * strings — no real masterdata/billing/iam entities are needed for a pure persistence test
 * (ADR-0008: loose uids, no FK constraints).
 *
 * <p>Uses the singleton-Testcontainer pattern via {@link AbstractIntegrationTest}.
 * {@code @Transactional} rolls back after each test to avoid cross-test contamination.
 */
@Transactional
class RegistrationSchemaIT extends AbstractIntegrationTest {

    // Placeholder loose-uid values for cross-module refs — no FK, no validation (ADR-0008).
    // 23-char strings; VARCHAR(26) has no format check in the DB.
    private static final String FAKE_DAY_UID       = "01FAKE000000000000DAY01";
    private static final String FAKE_REG_BILL_UID  = "01FAKE000000000000BIL01";
    private static final String FAKE_CON_BILL_UID  = "01FAKE000000000000BIL02";
    private static final String FAKE_CLINIC_UID    = "01FAKE000000000CLINIC01";
    private static final String FAKE_CLINICIAN_UID = "01FAKE000000CLINICIAN01";
    private static final String FAKE_PLAN_UID      = "01FAKE000000000000PLN01";

    @Autowired PatientRepository      patientRepository;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired VisitRepository        visitRepository;
    @Autowired ConsultationRepository consultationRepository;   // now clinical.domain (ADR-0022 D6)

    // -------------------------------------------------------------------------
    // Helper: build a minimal CASH outpatient patient
    // -------------------------------------------------------------------------

    private Patient cashPatient(String mrn, String searchKey,
                                String first, String middle, String last,
                                LocalDate dob, String gender, String phone) {
        return new Patient(mrn, searchKey, first, middle, last, dob, gender,
                PatientType.OUTPATIENT, PaymentType.CASH, "", phone, null, FAKE_DAY_UID);
    }

    // -------------------------------------------------------------------------
    // Round-trip part 1: Patient + Registration
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_patient_and_registration_persist_and_reload() {
        // Use a synthetic MRN the seq_mrno generator can NEVER produce, so this schema
        // round-trip test does not collide with patients committed by non-transactional ITs
        // sharing the singleton Testcontainer (the seq_mrno format is MRNO/{year}/{n}).
        final String schemaTestNo = "MRNO-SCHEMA-IT/2026/RT1";
        Patient patient = cashPatient(
                schemaTestNo,
                schemaTestNo + " John Michael Doe 0712345678",
                "John", "Michael", "Doe",
                LocalDate.of(1990, 5, 15), "MALE", "0712345678");
        Patient saved = patientRepository.saveAndFlush(patient);

        // Patient assertions
        Patient loaded = patientRepository.findByUid(saved.getUid()).orElseThrow();
        assertThat(loaded.getUid()).isNotNull().hasSize(26);
        assertThat(loaded.getNo()).isEqualTo(schemaTestNo);
        assertThat(loaded.getFirstName()).isEqualTo("John");
        assertThat(loaded.getMiddleName()).isEqualTo("Michael");
        assertThat(loaded.getLastName()).isEqualTo("Doe");
        assertThat(loaded.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 5, 15));
        assertThat(loaded.getGender()).isEqualTo("MALE");
        assertThat(loaded.getType()).isEqualTo(PatientType.OUTPATIENT);
        assertThat(loaded.getPaymentType()).isEqualTo(PaymentType.CASH);
        assertThat(loaded.getMembershipNo()).isEmpty();
        assertThat(loaded.getPhoneNo()).isEqualTo("0712345678");
        assertThat(loaded.getInsurancePlanUid()).isNull();
        assertThat(loaded.isActive()).isTrue();

        // Registration assertions
        Registration reg = new Registration(saved, FAKE_REG_BILL_UID, FAKE_DAY_UID);
        Registration savedReg = registrationRepository.saveAndFlush(reg);

        Registration loadedReg = registrationRepository.findByUid(savedReg.getUid()).orElseThrow();
        assertThat(loadedReg.getUid()).hasSize(26);
        assertThat(loadedReg.getPatient().getUid()).isEqualTo(saved.getUid());
        assertThat(loadedReg.getPatientBillUid()).isEqualTo(FAKE_REG_BILL_UID);
        assertThat(loadedReg.getStatus()).isEqualTo(RegistrationStatus.ACTIVE);
        assertThat(registrationRepository.findByPatient(saved)).isPresent();
    }

    // -------------------------------------------------------------------------
    // Round-trip part 2: FIRST Visit + PENDING Consultation
    // (Consultation now owned by clinical.domain — ADR-0022 D1/D6)
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_visit_and_consultation_persist_and_reload() {
        Patient patient = cashPatient(
                "MRNO-SCHEMA-IT/2026/V1",
                "MRNO-SCHEMA-IT/2026/V1 Tom  Lee 0744444444",
                "Tom", null, "Lee",
                LocalDate.of(1988, 9, 3), "MALE", "0744444444");
        Patient saved = patientRepository.saveAndFlush(patient);

        // FIRST Visit
        Visit visit = new Visit(saved, VisitSequence.FIRST, FAKE_DAY_UID);
        Visit savedVisit = visitRepository.saveAndFlush(visit);

        Visit loadedVisit = visitRepository.findByUid(savedVisit.getUid()).orElseThrow();
        assertThat(loadedVisit.getUid()).hasSize(26);
        assertThat(loadedVisit.getPatient().getUid()).isEqualTo(saved.getUid());
        assertThat(loadedVisit.getSequence()).isEqualTo(VisitSequence.FIRST);
        assertThat(loadedVisit.getType()).isEqualTo(PatientType.OUTPATIENT.name());
        assertThat(loadedVisit.getStatus()).isEqualTo(VisitStatus.PENDING);
        assertThat(visitRepository.findFirstByPatientOrderByCreatedAtDesc(saved)).isPresent();
        assertThat(visitRepository.findByPatientOrderByCreatedAtDesc(saved)).hasSize(1);

        // PENDING Consultation — now uses loose-uid constructor (ADR-0022 D2 + Correction).
        // V29 backfills patient_uid / visit_uid from the legacy patient_id / visit_id FKs and
        // then DROPs those id-FK columns; clinical references patient/visit by uid only.
        Consultation consultation = new Consultation(
                saved.getUid(),         // patientUid  (loose uid, ADR-0022 D2)
                savedVisit.getUid(),    // visitUid    (loose uid, ADR-0022 D2)
                FAKE_CLINIC_UID,
                FAKE_CLINICIAN_UID,
                FAKE_CON_BILL_UID,
                PaymentMode.CASH,       // PaymentMode from billing::api (ADR-0022 D5)
                false,                  // followUp
                false,                  // settled (CASH-OPD: must pay first)
                "",                     // membershipNo
                null,                   // insurancePlanUid
                FAKE_DAY_UID);
        Consultation savedConsult = consultationRepository.saveAndFlush(consultation);

        Consultation loadedConsult =
                consultationRepository.findByUid(savedConsult.getUid()).orElseThrow();
        assertThat(loadedConsult.getUid()).hasSize(26);
        assertThat(loadedConsult.getPatientUid()).isEqualTo(saved.getUid());
        assertThat(loadedConsult.getVisitUid()).isEqualTo(savedVisit.getUid());
        assertThat(loadedConsult.getStatus()).isEqualTo(ConsultationStatus.PENDING);
        assertThat(loadedConsult.getPaymentMode()).isEqualTo(PaymentMode.CASH);
        assertThat(loadedConsult.isFollowUp()).isFalse();
        assertThat(loadedConsult.isSettled()).isFalse();
        assertThat(loadedConsult.getPatientBillUid()).isEqualTo(FAKE_CON_BILL_UID);
        // Guard query via patientUid (ADR-0022 D6 — re-keyed from entity to uid)
        assertThat(consultationRepository.existsByPatientUidAndStatusIn(
                saved.getUid(), List.of(ConsultationStatus.PENDING))).isTrue();
    }

    // -------------------------------------------------------------------------
    // ULID uid assignment
    // -------------------------------------------------------------------------

    @Test
    void patient_uid_is_assigned_as_26_char_ulid_at_persist() {
        Patient patient = cashPatient(
                "MRNO-SCHEMA-IT/2026/V2",
                "MRNO-SCHEMA-IT/2026/V2 Alice  Smith 0799999999",
                "Alice", null, "Smith",
                LocalDate.of(1985, 1, 1), "FEMALE", null);
        Patient saved = patientRepository.saveAndFlush(patient);

        assertThat(saved.getUid())
                .isNotNull()
                .hasSize(26)
                .doesNotContain(" ");
    }

    // -------------------------------------------------------------------------
    // Insurance patient: plan uid and membership no present
    // -------------------------------------------------------------------------

    @Test
    void persist_insurance_patient_with_plan_and_membership() {
        final String insNo = "MRNO-SCHEMA-IT/2026/INS1";
        Patient patient = new Patient(
                insNo,
                insNo + " Mary Jane Jones 0711111111",
                "Mary", "Jane", "Jones",
                LocalDate.of(1978, 3, 22), "FEMALE",
                PatientType.OUTPATIENT,
                PaymentType.INSURANCE, "INS-MEMBER-001",
                "0711111111",
                FAKE_PLAN_UID,
                FAKE_DAY_UID);
        patientRepository.saveAndFlush(patient);

        Patient loaded = patientRepository.findByNo(insNo).orElseThrow();
        assertThat(loaded.getPaymentType()).isEqualTo(PaymentType.INSURANCE);
        assertThat(loaded.getMembershipNo()).isEqualTo("INS-MEMBER-001");
        assertThat(loaded.getInsurancePlanUid()).isEqualTo(FAKE_PLAN_UID);
    }

    // -------------------------------------------------------------------------
    // SUBSEQUENT_FOR_ADMISSION enum stored correctly (underscore form — build-spec §7)
    // -------------------------------------------------------------------------

    @Test
    void visit_subsequentForAdmission_sequence_stored_and_reloaded() {
        Patient patient = new Patient(
                "MRNO-SCHEMA-IT/2026/ADM1",
                "MRNO-SCHEMA-IT/2026/ADM1 Bob  Brown 0733333333",
                "Bob", null, "Brown",
                LocalDate.of(1995, 7, 10), "MALE",
                PatientType.INPATIENT,
                PaymentType.CASH, "",
                "0733333333", null, FAKE_DAY_UID);
        Patient savedPatient = patientRepository.saveAndFlush(patient);

        Visit admissionVisit = new Visit(
                savedPatient, VisitSequence.SUBSEQUENT_FOR_ADMISSION, FAKE_DAY_UID);
        Visit savedVisit = visitRepository.saveAndFlush(admissionVisit);

        Visit loaded = visitRepository.findByUid(savedVisit.getUid()).orElseThrow();
        assertThat(loaded.getSequence()).isEqualTo(VisitSequence.SUBSEQUENT_FOR_ADMISSION);
    }
}
