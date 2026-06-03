package com.otapp.hmis.registration.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.registration.application.dto.PatientDto;
import com.otapp.hmis.registration.application.dto.RegisterPatientRequest;
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
import com.otapp.hmis.shared.error.MissingInsuranceInformationException;
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

    private final PatientRepository patientRepository;
    private final RegistrationRepository registrationRepository;
    private final VisitRepository visitRepository;
    private final MrNumberGenerator mrNumberGenerator;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;
    private final PatientMapper patientMapper;

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
        String membershipNo = (req.paymentType() == PaymentType.CASH)
                ? ""
                : (req.membershipNo() != null ? req.membershipNo() : "");

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
                false);

        ChargeResult result = billingCommands.recordClinicalCharge(chargeReq, ctx);

        // ---- Step 6: Persist Registration (build-spec §2.3 step 6) ---------------------
        Registration registration = new Registration(patient, result.billUid(), ctx.dayUid());
        registrationRepository.save(registration);

        // ---- Step 7: Persist first Visit (build-spec §2.3 step 7) ----------------------
        Visit visit = new Visit(patient, VisitSequence.FIRST, ctx.dayUid());
        visitRepository.save(visit);

        // ---- Step 8: Audit (build-spec §2.3 step 8, ADR-0007) --------------------------
        // Audit entityType strings per spec: "registration.Patient"
        auditRecorder.record("registration.Patient", patient.getUid(),
                AuditAction.CREATE, ctx.actorUsername());

        return patientMapper.toDto(patient);
    }

    // ---------------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------------

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
