package com.otapp.hmis.registration.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.iam.lookup.ClinicianAffiliationService;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.registration.application.dto.ChangePatientTypeRequest;
import com.otapp.hmis.registration.application.dto.ChangePaymentTypeRequest;
import com.otapp.hmis.registration.application.dto.ConsultationDto;
import com.otapp.hmis.registration.application.dto.PatientDto;
import com.otapp.hmis.registration.application.dto.RegisterPatientRequest;
import com.otapp.hmis.registration.application.dto.SendToDoctorRequest;
import com.otapp.hmis.registration.application.dto.UpdatePatientRequest;
import com.otapp.hmis.registration.domain.Consultation;
import com.otapp.hmis.registration.domain.ConsultationRepository;
import com.otapp.hmis.registration.domain.ConsultationStatus;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.registration.domain.Registration;
import com.otapp.hmis.registration.domain.RegistrationRepository;
import com.otapp.hmis.registration.domain.Visit;
import com.otapp.hmis.registration.domain.VisitRepository;
import com.otapp.hmis.registration.domain.VisitSequence;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.MissingInsuranceInformationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the patient registration workflow (build-spec §2.3, PatientServiceImpl.java:220-420).
 *
 * <p>One {@link Transactional} boundary covers all 8 steps (ADR-0014 §5): Patient persist,
 * billing charge, Registration persist, Visit persist, and audit record are atomic. If any
 * step fails the entire transaction rolls back, leaving no orphan rows.
 *
 * <p>The {@link com.otapp.hmis.billing.api.BillingCommands#recordClinicalCharge} call runs
 * in the caller's transaction (propagation REQUIRED, ADR-0008 §4) — it is NOT async and NOT
 * in a nested {@code REQUIRES_NEW} transaction.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Registration entry point: PatientResource.java:288-305</li>
 *   <li>Orchestration: PatientServiceImpl.java:220-420</li>
 *   <li>Insurance guard: PatientResource.java:299-301</li>
 *   <li>CASH plan-force-null: PatientServiceImpl.java:355-362 (inferred from consistency rule)</li>
 *   <li>MRN assignment: PatientServiceImpl.java:248-254</li>
 *   <li>Registration creation: PatientServiceImpl.java:293-302</li>
 *   <li>Visit creation: PatientServiceImpl.java:409-419</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PatientRegistrationProcess {

    /** Stable audit entity-type string for Patient (ADR-0007). */
    private static final String AUDIT_ENTITY_PATIENT = "registration.Patient";

    /** Stable audit entity-type string for Consultation (ADR-0007). */
    private static final String AUDIT_ENTITY_CONSULTATION = "registration.Consultation";

    private final PatientRepository patientRepository;
    private final RegistrationRepository registrationRepository;
    private final VisitRepository visitRepository;
    private final ConsultationRepository consultationRepository;
    private final MrNumberGenerator mrNumberGenerator;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;
    private final PatientMapper patientMapper;
    private final ClinicianAffiliationService clinicianAffiliationService;
    private final ConsultationMapper consultationMapper;

    /**
     * Register a new patient — 8-step atomic workflow (build-spec §2.3).
     *
     * <p>Step 1: Validate insurance consistency.
     * Step 2: Assign MRN from {@code seq_mrno}.
     * Step 3: Build search key from the 5-field composition (build-spec §2.2, CR-09).
     * Step 4: Persist {@link Patient}.
     * Step 5: Call {@code billing.recordClinicalCharge(REGISTRATION)} → {@link ChargeResult}.
     * Step 6: Persist {@link Registration} linking patient to bill uid.
     * Step 7: Persist first {@link Visit} (FIRST / PENDING).
     * Step 8: Record audit log (CREATE) for the patient.
     *
     * @param req the validated registration request
     * @param ctx the transaction audit context (dayUid, timestamp, actor)
     * @return the mapped {@link PatientDto} for the 201 response
     * @throws MissingInsuranceInformationException (422) if INSURANCE with no plan or no membership
     */
    @Transactional
    public PatientDto register(RegisterPatientRequest req, TxAuditContext ctx) {

        // ---- Step 1: Insurance consistency guard (build-spec §2.3 step 1) ---------------
        validateInsuranceConsistency(req);

        // Normalise: CASH patients must never carry a plan uid (DB CHECK mirrors this)
        String insurancePlanUid = (req.paymentType() == PaymentType.CASH)
                ? null
                : req.insurancePlanUid();
        String rawMembership = req.membershipNo() != null ? req.membershipNo() : "";
        String membershipNo = (req.paymentType() == PaymentType.CASH) ? "" : rawMembership;

        // ---- Step 2: Allocate MRN (build-spec §2.1, CR-02) ------------------------------
        String no = mrNumberGenerator.next();

        // ---- Step 3: Build search key (build-spec §2.2, CR-09) -------------------------
        String searchKey = SearchKeyBuilder.build(
                no,
                req.firstName(),
                req.middleName(),
                req.lastName(),
                req.phoneNo());

        // ---- Step 4: Persist Patient (build-spec §2.3 step 4) --------------------------
        PatientType type = req.patientType() != null ? req.patientType() : PatientType.OUTPATIENT;

        Patient patient = new Patient(
                no,
                searchKey,
                req.firstName(),
                req.middleName(),
                req.lastName(),
                req.dateOfBirth(),
                req.gender(),
                type,
                req.paymentType(),
                membershipNo,
                req.phoneNo(),
                insurancePlanUid,
                ctx.dayUid());

        // Populate optional contact / kin fields via the demographics mutator
        patient.updateDemographics(
                req.firstName(),
                req.middleName(),
                req.lastName(),
                req.dateOfBirth(),
                req.gender(),
                req.phoneNo(),
                req.address(),
                req.email(),
                req.nationality(),
                req.nationalId(),
                req.passportNo(),
                req.kinFullName(),
                req.kinRelationship(),
                req.kinPhoneNo(),
                searchKey);

        patient = patientRepository.save(patient);

        // ---- Step 5: Registration fee via billing engine (build-spec §2.3 step 5) -------
        // kind=REGISTRATION, serviceUid=null, qty=1, inpatient=false (CR-12, build-spec §0 item 2)
        ChargeRequest chargeReq = new ChargeRequest(
                patient.getUid(),
                insurancePlanUid,
                membershipNo.isEmpty() ? null : membershipNo,
                ServiceKind.REGISTRATION,
                null,                       // serviceUid null for REGISTRATION (ChargeRequest.java:19)
                BigDecimal.ONE,
                mapPaymentMode(req.paymentType()),
                false,                      // inpatient: always false at registration (CR-12)
                false);                     // followUp: REGISTRATION is never a follow-up

        ChargeResult result = billingCommands.recordClinicalCharge(chargeReq, ctx);

        // ---- Step 6: Persist Registration (build-spec §2.3 step 6) ---------------------
        Registration registration = new Registration(patient, result.billUid(), ctx.dayUid());
        registrationRepository.save(registration);

        // ---- Step 7: Persist first Visit (build-spec §2.3 step 7) ----------------------
        Visit visit = new Visit(patient, VisitSequence.FIRST, ctx.dayUid());
        visitRepository.save(visit);

        // ---- Step 8: Audit (build-spec §2.3 step 8, ADR-0007) --------------------------
        // Audit entityType strings per spec: AUDIT_ENTITY_PATIENT
        auditRecorder.record(AUDIT_ENTITY_PATIENT, patient.getUid(),
                AuditAction.CREATE, ctx.actorUsername());

        return patientMapper.toDto(patient);
    }

    // ---------------------------------------------------------------------------------
    // C4 — update demographics + type/payment-type flip guards
    // ---------------------------------------------------------------------------------

    /**
     * Update mutable demographic and kin fields for an existing patient.
     *
     * <p>Payment type, MRN ({@code no}), and patient type are NOT touched here — they have
     * dedicated endpoints (build-spec §8 C4, §1.3).
     *
     * <p>The search key is recomputed from the updated name/phone fields so the GIN
     * trigram index stays consistent (build-spec §2.2, CR-09).
     *
     * @param uid    the patient ULID
     * @param req    validated demographics update request
     * @param ctx    transaction audit context
     * @return the updated {@link PatientDto}
     * @throws NotFoundException (404) if no patient with the given uid exists
     * Legacy citation: PatientResource.java:378-395.
     */
    @Transactional
    public PatientDto updateDemographics(String uid, UpdatePatientRequest req, TxAuditContext ctx) {

        Patient patient = requirePatient(uid);

        // Recompute search key from the new name/phone values (build-spec §2.2, CR-09)
        String updatedSearchKey = SearchKeyBuilder.build(
                patient.getNo(),
                req.firstName(),
                req.middleName(),
                req.lastName(),
                req.phoneNo());

        patient.updateDemographics(
                req.firstName(),
                req.middleName(),
                req.lastName(),
                req.dateOfBirth(),
                req.gender(),
                req.phoneNo(),
                req.address(),
                req.email(),
                req.nationality(),
                req.nationalId(),
                req.passportNo(),
                req.kinFullName(),
                req.kinRelationship(),
                req.kinPhoneNo(),
                updatedSearchKey);

        patientRepository.save(patient);

        auditRecorder.record(AUDIT_ENTITY_PATIENT, patient.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        return patientMapper.toDto(patient);
    }

    /**
     * Toggle the patient's type between OUTPATIENT and OUTSIDER, applying all legacy
     * guards verbatim (build-spec §8 C4, PatientResource.java:421-506).
     *
     * <p>Guard table:
     * <ul>
     *   <li><b>null current type</b>: treated as OUTPATIENT (defensive — PatientResource.java:410-414).</li>
     *   <li><b>OUTPATIENT → OUTSIDER</b>: blocked if the patient has any {@code PENDING}
     *       consultation.  Throws {@link InvalidPatientOperationException} (422) with verbatim
     *       legacy message.  Inc-05 widens the status set to include IN-PROCESS and TRANSFERRED
     *       (inc-05 widens these statuses).</li>
     *   <li><b>OUTSIDER → OUTPATIENT</b>: deferred — NonConsultation order-clearance check
     *       lands with inc-05/06 (REG-3); the flip proceeds unconditionally here.</li>
     *   <li><b>INPATIENT current</b>: always blocked (PatientResource.java:499-500).</li>
     *   <li><b>DECEASED or other current</b>: always blocked (PatientResource.java:505).</li>
     *   <li><b>INPATIENT or DECEASED target</b>: rejected — only OUTPATIENT↔OUTSIDER toggle
     *       is legal here (build-spec §2.3 state machine).</li>
     * </ul>
     *
     * @param uid    the patient ULID
     * @param req    the desired target type (only OUTPATIENT or OUTSIDER accepted)
     * @param ctx    transaction audit context
     * @return the updated {@link PatientDto}
     * @throws NotFoundException              (404) if no patient with the given uid exists
     * @throws InvalidPatientOperationException (422) on any guard violation
     * Legacy citation: PatientResource.java:398-506 (change_type).
     */
    @Transactional
    public PatientDto changePatientType(String uid, ChangePatientTypeRequest req, TxAuditContext ctx) {

        Patient patient = requirePatient(uid);

        // Reject INPATIENT / DECEASED as target — only OUTPATIENT↔OUTSIDER is legal
        if (req.targetType() == PatientType.INPATIENT || req.targetType() == PatientType.DECEASED) {
            throw new InvalidPatientOperationException("Patient type could not be changed.");
        }

        PatientType current = patient.getType() != null ? patient.getType() : PatientType.OUTPATIENT;

        switch (current) {
            case OUTPATIENT -> {
                // OUTPATIENT → OUTSIDER: block if patient has an active (PENDING) consultation
                // Inc-05 widens these statuses to include IN-PROCESS and TRANSFERRED.
                if (req.targetType() == PatientType.OUTSIDER
                        && consultationRepository.existsByPatientAndStatus(
                                patient, ConsultationStatus.PENDING)) {
                    throw new InvalidPatientOperationException(
                            "Can not change patient type, the patient has an active consultation.");
                }
                patient.changeType(req.targetType());
            }
            // OUTSIDER → OUTPATIENT: deferred — NonConsultation order-clearance check
            // lands with inc-05/06 (REG-3); flip proceeds unconditionally in inc-03.
            case OUTSIDER -> patient.changeType(req.targetType());
            case INPATIENT ->
                // Legacy: PatientResource.java:499-500 — verbatim message
                throw new InvalidPatientOperationException(
                        "This operation is not allowed for inpatients");
            default ->
                // DECEASED or any future value — verbatim legacy catch-all (PatientResource.java:505)
                throw new InvalidPatientOperationException("Patient type could not be changed.");
        }

        patientRepository.save(patient);

        auditRecorder.record(AUDIT_ENTITY_PATIENT, patient.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        return patientMapper.toDto(patient);
    }

    /**
     * Change the patient's payment classification (CASH ↔ INSURANCE), applying legacy
     * guards and the CR-03 security gate (build-spec §8 C4, PatientResource.java:359-373).
     *
     * <p>Rules:
     * <ul>
     *   <li><b>Target INSURANCE</b>: {@code insurancePlanUid} must be non-null AND
     *       {@code membershipNo} must be non-blank; else
     *       {@link MissingInsuranceInformationException} (422).
     *       Sets plan + membership + {@code paymentType=INSURANCE}.</li>
     *   <li><b>Any non-INSURANCE (i.e. CASH)</b>: collapses to CASH —
     *       {@code insurancePlanUid=null}, {@code membershipNo=""}, {@code paymentType=CASH}
     *       (PatientResource.java:368-373 verbatim).</li>
     *   <li><b>Admissions guard</b>: PatientResource.java:366-367 ("Could not change. Patient
     *       has an ongoing medical operation") — DEFERRED-ENFORCEMENT no-op stub; admissions
     *       do not exist until inc-06.
     *       <!-- CR-19-style deferred: no admissions until inc-06 --></li>
     * </ul>
     *
     * @param uid    the patient ULID
     * @param req    the desired payment type + optional insurance details
     * @param ctx    transaction audit context
     * @return the updated {@link PatientDto}
     * @throws NotFoundException                   (404) if no patient with the given uid exists
     * @throws MissingInsuranceInformationException (422) if INSURANCE target without plan/membership
     * Legacy citation: PatientResource.java:359-373 (change_payment_type, legacy ungated → CR-03).
     */
    @Transactional
    public PatientDto changePaymentType(String uid, ChangePaymentTypeRequest req, TxAuditContext ctx) {

        Patient patient = requirePatient(uid);

        // DEFERRED-ENFORCEMENT (CR-19-style): the admissions guard from PatientResource.java:366-367
        // ("Could not change. Patient has an ongoing medical operation") is a no-op stub in inc-03.
        // When inc-06 lands, add: existsByPatientAndActiveAdmission check → InvalidPatientOperationException.

        if (req.paymentType() == PaymentType.INSURANCE
                && (req.insurancePlanUid() == null
                        || req.membershipNo() == null
                        || req.membershipNo().isBlank())) {
            // INSURANCE requires both plan uid and non-blank membership number
            throw new MissingInsuranceInformationException();
        }

        if (req.paymentType() == PaymentType.INSURANCE) {
            patient.changePaymentType(PaymentType.INSURANCE,
                    req.insurancePlanUid(), req.membershipNo());
        } else {
            // Any non-INSURANCE → collapse to CASH (PatientResource.java:368-373 verbatim)
            patient.changePaymentType(PaymentType.CASH, null, "");
        }

        patientRepository.save(patient);

        auditRecorder.record(AUDIT_ENTITY_PATIENT, patient.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        return patientMapper.toDto(patient);
    }

    // ---------------------------------------------------------------------------------
    // C6 — send-to-doctor (consultation booking + consultation-fee charge)
    // ---------------------------------------------------------------------------------

    /**
     * Send a patient to a doctor — creates a PENDING consultation and the associated
     * consultation-fee charge in one atomic transaction (build-spec §3.2, CR-22).
     *
     * <p>Equivalent to legacy {@code do_consultation} (PatientServiceImpl.java:425-475).
     * Steps:
     * <ol>
     *   <li>Load patient (404 if absent).</li>
     *   <li>Guard: patient must be OUTPATIENT (422).</li>
     *   <li>Guard: clinician must be affiliated with the selected clinic (422).</li>
     *   <li>Guard: no existing PENDING consultation for the patient (422).</li>
     *   <li>Record consultation-fee charge via billing engine; for follow-up the bill is
     *       NONE / zero (CR-20, PatientServiceImpl.java:467-469).</li>
     *   <li>Persist a SUBSEQUENT Visit unconditionally (no same-day dedup, AC3.6).</li>
     *   <li>Persist Consultation (PENDING).</li>
     *   <li>Audit Consultation CREATE.</li>
     * </ol>
     *
     * <p>Atomicity: the charge call runs in this method's transaction (propagation REQUIRED).
     * A charge failure (e.g. missing CONSULTATION price) rolls back the entire unit —
     * no Consultation row and no SUBSEQUENT Visit are persisted. The bill uid is obtained
     * before persisting either, so a charge failure leaves no orphan rows.
     *
     * <p>DEFERRED stubs (inc-05/06):
     * <ul>
     *   <li>ConsultationTransfer guard (TRANSFERED status) — inc-05</li>
     *   <li>IN-PROCESS status guard — inc-05</li>
     *   <li>Admission / inpatient guard — inc-06</li>
     * </ul>
     *
     * @param uid  the patient ULID
     * @param req  the send-to-doctor request (clinicUid, clinicianUserUid, followUp)
     * @param ctx  transaction audit context
     * @return the mapped {@link ConsultationDto} for the 201 response
     * @throws NotFoundException               (404) if no patient with the given uid exists
     * @throws InvalidPatientOperationException (422) on any guard violation
     * Legacy citation: PatientServiceImpl.java:425-475 (do_consultation).
     */
    @Transactional
    public ConsultationDto sendToDoctor(String uid, SendToDoctorRequest req, TxAuditContext ctx) {

        // Step 1 — load patient (404)
        Patient patient = requirePatient(uid);

        // Step 2 — OUTPATIENT guard (PatientServiceImpl.java:432-438)
        if (patient.getType() != PatientType.OUTPATIENT) {
            throw new InvalidPatientOperationException(
                    "Please change patient type to OUTPATIENT to continue with operation");
        }

        // Step 3 — clinician affiliation gate (CR-08, build-spec §3.2)
        // Equivalent to legacy clinician-active + clinic-pairing check. The iam module
        // owns the affiliation set; registration depends only on iam::lookup (ADR-0008).
        if (!clinicianAffiliationService.clinicUidsOf(req.clinicianUserUid())
                .contains(req.clinicUid())) {
            throw new InvalidPatientOperationException(
                    "Clinician is not affiliated with the selected clinic");
        }

        // Step 4 — no existing PENDING consultation guard (PatientServiceImpl.java:443-448)
        // inc-05/06: widen to TRANSFERED + IN-PROCESS statuses; add admission guard
        if (consultationRepository.existsByPatientAndStatus(patient, ConsultationStatus.PENDING)) {
            throw new InvalidPatientOperationException(
                    "Patient has pending or held consultation, please consider freeing the patient");
        }
        // inc-05: TRANSFERED status guard (ConsultationTransfer) — DEFERRED
        // inc-05: IN-PROCESS status guard — DEFERRED
        // inc-06: admission / inpatient guard — DEFERRED

        // Step 5 — consultation-fee charge via billing engine (build-spec §3.2 step 5)
        // For follow-up: billing engine creates a NONE bill with zero amounts (CR-20).
        // For non-followUp INSURANCE patient not covered at clinic: billing hard-fails 422
        // (PlanNotAvailableForClinicException) — intentional PARITY, rolls back entire tx.
        // NOTE: charge is called BEFORE Visit + Consultation persist so that a charge failure
        // leaves no orphan Visit or Consultation rows in the database (atomicity by ordering).
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(
                new ChargeRequest(
                        patient.getUid(),
                        patient.getInsurancePlanUid(),
                        patient.getMembershipNo() != null && !patient.getMembershipNo().isBlank()
                                ? patient.getMembershipNo() : null,
                        ServiceKind.CONSULTATION,
                        req.clinicUid(),          // serviceUid = clinicUid for CONSULTATION pricing
                        BigDecimal.ONE,
                        mapPaymentMode(patient.getPaymentType()),
                        false,                    // inpatient: always false for OPD send-to-doctor
                        req.followUp()),
                ctx);

        // Step 6 — persist SUBSEQUENT Visit unconditionally (no same-day dedup, AC3.6)
        // PatientServiceImpl.java:499-512 (subsequent visit creation)
        Visit visit = new Visit(patient, VisitSequence.SUBSEQUENT, ctx.dayUid());
        visitRepository.save(visit);

        // Step 7 — persist Consultation (PENDING)
        // Consultation.java constructor: status defaults to PENDING (build-spec §3.2 step 7)
        Consultation consultation = new Consultation(
                patient,
                visit,
                req.clinicUid(),
                req.clinicianUserUid(),
                chargeResult.billUid(),
                patient.getPaymentType(),
                req.followUp(),
                ctx.dayUid());
        consultationRepository.save(consultation);

        // Step 8 — audit Consultation CREATE (ADR-0007)
        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, consultation.getUid(),
                AuditAction.CREATE, ctx.actorUsername());

        // ConsultationBooked after-commit event is reporting-only → SKIP/defer in inc-03
        // (build-spec §3.2 note; do not add an event publisher here)

        return consultationMapper.toDto(consultation);
    }

    // ---------------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------------

    /**
     * Load a {@link Patient} by ULID or throw {@link NotFoundException} (404).
     *
     * @param uid the patient ULID
     * @return the patient aggregate root
     * @throws NotFoundException if no patient with the given uid exists
     */
    private Patient requirePatient(String uid) {
        return patientRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + uid));
    }

    /**
     * Validate insurance consistency (build-spec §2.3 step 1).
     *
     * <p>INSURANCE patients MUST supply both {@code insurancePlanUid} (non-null) and
     * {@code membershipNo} (non-blank). Mirrors the DB {@code ck_patients_insurance_consistency}
     * check constraint and legacy PatientResource.java:299-301.
     */
    private static void validateInsuranceConsistency(RegisterPatientRequest req) {
        if (req.paymentType() == PaymentType.INSURANCE) {
            if (req.insurancePlanUid() == null
                    || req.membershipNo() == null
                    || req.membershipNo().isBlank()) {
                throw new MissingInsuranceInformationException();
            }
        }
        // CASH: plan uid is irrelevant; normalised to null in the caller
    }

    /**
     * Map registration {@link PaymentType} to billing {@link PaymentMode}.
     *
     * <p>CASH → CASH, INSURANCE → INSURANCE (two-value enums are structurally identical;
     * this explicit mapping keeps the registration module free of direct coupling to
     * billing.domain beyond what is exposed via billing.api — ADR-0008).
     */
    private static PaymentMode mapPaymentMode(PaymentType paymentType) {
        return switch (paymentType) {
            case CASH      -> PaymentMode.CASH;
            case INSURANCE -> PaymentMode.INSURANCE;
        };
    }
}
