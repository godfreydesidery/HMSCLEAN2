package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.api.SettlementPolicy;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.application.dto.IssueRequest;
import com.otapp.hmis.clinical.application.dto.PrescriptionBatchDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionBatchRequest;
import com.otapp.hmis.clinical.application.dto.PrescriptionDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionRequest;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionBatch;
import com.otapp.hmis.clinical.domain.PrescriptionBatchRepository;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.PrescriptionStatus;
import com.otapp.hmis.masterdata.lookup.MedicineLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing the Prescription aggregate lifecycle (inc-05 C10).
 *
 * <p><strong>State machine (EXACTLY two statuses — Prescription.java:50):</strong>
 * <ul>
 *   <li><strong>prescribe</strong> (NOT-GIVEN): guards → exactly-one-encounter,
 *       medicine exists (MedicineLookup), HARD DUPLICATE-DRUG block (same medicine on same
 *       encounter → 422), consultation OUTPATIENT / non-consultation OUTSIDER.
 *       Creates billing charge (kind=MEDICINE, serviceUid=medicineUid, qty=prescribed qty).
 *       balance=qty, issued=0. Stamps ordered_*.</li>
 *   <li><strong>issueMedicine</strong> (NOT-GIVEN → GIVEN):
 *       guard status==NOT-GIVEN ("not a pending prescription");
 *       issued must equal full prescribed qty ("You can only issue the prescribed qty");
 *       issued must be > 0 ("Invalid issue value");
 *       issued must not exceed balance ("Invalid issue value");
 *       [STOCK check DEFERRED — pharmacy module];
 *       on success: issued=qty, balance=0, issuePharmacyUid set, status GIVEN,
 *       approved_* audit stamped. (PatientResource.java:3217-3245.)</li>
 *   <li><strong>delete</strong> (NOT-GIVEN only): hard delete;
 *       credit-note DEFERRED.</li>
 * </ul>
 *
 * <p><strong>CR-INC05-05 — corrected non-consultation duplicate check:</strong>
 * The legacy {@code existsByConsultationAndMedicine} on an empty Optional for the
 * non-consultation path would NPE at runtime. The corrected behaviour uses a dedicated
 * {@code existsByNonConsultationAndMedicineUid} query so the duplicate rule evaluates
 * correctly for OUTSIDER prescriptions instead of crashing.
 *
 * <p><strong>Settlement flag (CR-INC05-01):</strong>
 * Set at prescribe time: true for INSURANCE/COVERED, false for CASH-OPD.
 * Cash-PAID→settled=true propagation is DEFERRED (same pattern as LabTest).
 *
 * <p><strong>DEFERRED:</strong>
 * <ul>
 *   <li>STOCK check + pharmacy inventory decrement (pharmacy module).</li>
 *   <li>patient_prescription_charts write path (admissions module).</li>
 *   <li>delete credit-note seam (billing cancel API not yet published).</li>
 *   <li>Cash-PAID→settled=true propagation (billing event seam).</li>
 * </ul>
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Prescription.java:38-144 (entity shape, status, audit)</li>
 *   <li>PatientResource.java:3217-3245 (issueMedicine)</li>
 *   <li>PatientServiceImpl.java (save_prescription, duplicate guard)</li>
 *   <li>PatientResource.java:4496, 4556 (alert finders — C11)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class PrescriptionService implements PrescriptionPort {

    private final PrescriptionRepository        prescriptionRepository;
    private final PrescriptionBatchRepository   batchRepository;
    private final ConsultationRepository        consultationRepository;
    private final NonConsultationRepository     nonConsultationRepository;
    private final MedicineLookup                medicineLookup;
    private final BillingCommands               billingCommands;
    private final AuditRecorder                 auditRecorder;
    private final PrescriptionMapper            prescriptionMapper;

    private static final String AUDIT_ENTITY = "clinical.Prescription";
    private static final String AUDIT_BATCH  = "clinical.PrescriptionBatch";

    // =========================================================================
    // Prescribe — consultation path
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards:
     * <ol>
     *   <li>Consultation must exist (404).</li>
     *   <li>Medicine must exist in masterdata (404 "Medicine not found").</li>
     *   <li>No duplicate medicine on this consultation (422 verbatim legacy message).</li>
     * </ol>
     *
     * <p>Billing: ChargeRequest built with kind=MEDICINE, serviceUid=medicineUid,
     * qty=prescribed qty (PatientServiceImpl.java — medicine qty-multiplied charge).
     */
    @Override
    @Transactional
    public PrescriptionDto prescribeForConsultation(String consultationUid,
                                                     PrescriptionRequest request,
                                                     TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        requireMedicineExists(request.medicineUid());
        guardNoDuplicateOnConsultation(consultation, request.medicineUid());

        PaymentMode paymentMode = toPaymentMode(request.paymentType() != null
                ? request.paymentType() : consultation.getPaymentMode().name());
        String membershipNo = request.membershipNo() != null
                ? request.membershipNo() : consultation.getMembershipNo();
        String insurancePlanUid = request.insurancePlanUid() != null
                ? request.insurancePlanUid() : consultation.getInsurancePlanUid();

        ChargeRequest chargeRequest = new ChargeRequest(
                consultation.getPatientUid(),
                insurancePlanUid,
                membershipNo,
                ServiceKind.MEDICINE,
                request.medicineUid(),
                request.qty(),
                paymentMode,
                false,  // outpatient
                false   // not a follow-up
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);
        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        Prescription p = Prescription.forConsultation(
                consultation,
                request.medicineUid(),
                chargeResult.billUid(),
                settled,
                request.qty(),
                request.dosage(),
                request.frequency(),
                request.route(),
                request.days(),
                request.reference(),
                request.instructions(),
                paymentMode.name(),
                membershipNo,
                insurancePlanUid,
                request.clinicianUserUid(),
                ctx.actorUsername(),
                ctx.dayUid(),
                now);

        Prescription saved = prescriptionRepository.save(p);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return prescriptionMapper.toDto(saved);
    }

    // =========================================================================
    // Prescribe — non-consultation (OUTSIDER/walk-in) path
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards:
     * <ol>
     *   <li>NonConsultation must exist (404).</li>
     *   <li>Medicine must exist in masterdata (404 "Medicine not found").</li>
     *   <li>CR-INC05-05 corrected duplicate check: uses
     *       {@code existsByNonConsultationAndMedicineUid} (NOT the legacy consultation-based
     *       Optional path that NPE'd). Returns 422 on duplicate.</li>
     * </ol>
     */
    @Override
    @Transactional
    public PrescriptionDto prescribeForNonConsultation(String nonConsultationUid,
                                                        PrescriptionRequest request,
                                                        TxAuditContext ctx) {
        NonConsultation nonConsultation = requireNonConsultation(nonConsultationUid);
        requireMedicineExists(request.medicineUid());
        // CR-INC05-05: dedicated non-consultation duplicate check (not the legacy NPE path)
        guardNoDuplicateOnNonConsultation(nonConsultation, request.medicineUid());

        String patientUid = nonConsultation.getPatientUid();
        String paymentTypeStr = request.paymentType() != null && !request.paymentType().isBlank()
                ? request.paymentType() : nonConsultation.getPaymentType();
        String membershipNo = request.membershipNo() != null
                ? request.membershipNo() : nonConsultation.getMembershipNo();
        String insurancePlanUid = request.insurancePlanUid() != null
                ? request.insurancePlanUid() : nonConsultation.getInsurancePlanUid();

        PaymentMode paymentMode = toPaymentMode(paymentTypeStr);

        ChargeRequest chargeRequest = new ChargeRequest(
                patientUid,
                insurancePlanUid,
                membershipNo,
                ServiceKind.MEDICINE,
                request.medicineUid(),
                request.qty(),
                paymentMode,
                false,  // outsider = not inpatient
                false
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);
        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        Prescription p = Prescription.forNonConsultation(
                nonConsultation,
                patientUid,
                request.medicineUid(),
                chargeResult.billUid(),
                settled,
                request.qty(),
                request.dosage(),
                request.frequency(),
                request.route(),
                request.days(),
                request.reference(),
                request.instructions(),
                paymentMode.name(),
                membershipNo,
                insurancePlanUid,
                request.clinicianUserUid(),
                ctx.actorUsername(),
                ctx.dayUid(),
                now);

        Prescription saved = prescriptionRepository.save(p);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return prescriptionMapper.toDto(saved);
    }

    // =========================================================================
    // Dispense (issueMedicine) — NOT-GIVEN → GIVEN
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>All-or-nothing rule (PatientResource.java:3230): issued must equal the full
     * prescribed qty. Partial dispense is NOT allowed.
     * STOCK check is DEFERRED — pharmacy module not yet built.
     */
    @Override
    @Transactional
    public PrescriptionDto issueMedicine(String prescriptionUid, IssueRequest request,
                                          TxAuditContext ctx) {
        Prescription p = requirePrescription(prescriptionUid);
        // Domain method enforces all guards + state mutation + approved_* audit
        p.issue(request.issued(), request.issuePharmacyUid(),
                ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, p.getUid(), AuditAction.UPDATE,
                ctx.actorUsername());
        return prescriptionMapper.toDto(p);
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guard: status must be NOT-GIVEN.
     *
     * <p><strong>DEFERRED — credit-note seam:</strong>
     * If the bill has already been PAID (CASH-OPD paid at cashier), a credit-note should be
     * raised. The billing module does not yet publish a cancel/credit-note command via
     * {@code billing.api}. Until the seam lands:
     * <ul>
     *   <li>The prescription IS deleted (correct for the common unpaid case).</li>
     *   <li>No credit-note is raised for already-PAID bills.</li>
     * </ul>
     * TODO: Wire billing cancel seam when billing.api publishes CancelBillCommand.
     */
    @Override
    @Transactional
    public void delete(String prescriptionUid, TxAuditContext ctx) {
        Prescription p = requirePrescription(prescriptionUid);
        if (p.getStatus() != PrescriptionStatus.NOT_GIVEN) {
            throw new InvalidPatientOperationException(
                    "Only a pending prescription can be deleted");
        }
        // TODO: If billing bill is PAID, raise a credit-note (deferred — billing cancel seam
        // not yet published in billing.api; see PrescriptionService javadoc).
        prescriptionRepository.delete(p);
        auditRecorder.record(AUDIT_ENTITY, prescriptionUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PrescriptionDto getByUid(String prescriptionUid) {
        return prescriptionMapper.toDto(requirePrescription(prescriptionUid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionDto> listByConsultation(String consultationUid) {
        Consultation consultation = requireConsultation(consultationUid);
        return prescriptionMapper.toDtoList(
                prescriptionRepository.findByConsultationOrderByCreatedAtAsc(consultation));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionDto> byPatient(String patientUid) {
        return prescriptionMapper.toDtoList(
                prescriptionRepository.findByPatientUidOrderByCreatedAtDesc(patientUid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionDto> pharmacyWorklist() {
        return prescriptionMapper.toDtoList(
                prescriptionRepository.findByStatusOrderByCreatedAtAsc(
                        PrescriptionStatus.NOT_GIVEN));
    }

    // =========================================================================
    // Prescription batches
    // =========================================================================

    @Override
    @Transactional
    public PrescriptionBatchDto addBatch(String prescriptionUid,
                                          PrescriptionBatchRequest request,
                                          TxAuditContext ctx) {
        Prescription p = requirePrescription(prescriptionUid);
        PrescriptionBatch batch = new PrescriptionBatch(
                p,
                request.no(),
                request.manufacturedDate(),
                request.expiryDate(),
                request.qty());
        PrescriptionBatch saved = batchRepository.save(batch);
        auditRecorder.record(AUDIT_BATCH, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return prescriptionMapper.toBatchDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionBatchDto> listBatches(String prescriptionUid) {
        Prescription p = requirePrescription(prescriptionUid);
        return prescriptionMapper.toBatchDtoList(
                batchRepository.findByPrescriptionOrderByCreatedAtAsc(p));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Prescription requirePrescription(String uid) {
        return prescriptionRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Prescription not found: " + uid));
    }

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }

    private NonConsultation requireNonConsultation(String uid) {
        return nonConsultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("NonConsultation not found: " + uid));
    }

    /**
     * Verify the medicineUid resolves in the masterdata catalog.
     * Error message: "Medicine not found" (mirrors the DiagnosisType / LabTestType pattern).
     */
    private void requireMedicineExists(String medicineUid) {
        if (!medicineLookup.existsByUid(medicineUid)) {
            throw new NotFoundException("Medicine not found");
        }
    }

    /**
     * Duplicate guard — consultation path.
     * Verbatim legacy message: "Duplicate medicine is not allowed for this encounter"
     * (PatientServiceImpl.java parity — same-medicine-same-consultation → throw).
     */
    private void guardNoDuplicateOnConsultation(Consultation consultation, String medicineUid) {
        if (prescriptionRepository.existsByConsultationAndMedicineUid(
                consultation, medicineUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate medicine is not allowed for this encounter");
        }
    }

    /**
     * Duplicate guard — non-consultation path.
     *
     * <p><strong>CR-INC05-05 corrected behaviour:</strong>
     * The legacy code called {@code existsByConsultationAndMedicine} on what might be an empty
     * Optional (when the non-consultation had no consultation), which caused an NPE. This
     * implementation uses the dedicated {@code existsByNonConsultationAndMedicineUid} query
     * so the duplicate rule evaluates correctly for OUTSIDER prescriptions.
     *
     * <p>Same verbatim message as the consultation path.
     */
    private void guardNoDuplicateOnNonConsultation(NonConsultation nonConsultation,
                                                    String medicineUid) {
        if (prescriptionRepository.existsByNonConsultationAndMedicineUid(
                nonConsultation, medicineUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate medicine is not allowed for this encounter");
        }
    }

    /**
     * Derive the local settled flag from the billing ChargeResult and payment context.
     * Same rule as LabTestService (CR-INC05-01, ADR-0022 D4).
     */
    private static boolean isSettledFromCharge(ChargeResult chargeResult,
                                                PaymentMode paymentMode,
                                                boolean inpatient) {
        if (!SettlementPolicy.requiresPrepayment(paymentMode, inpatient, false)) {
            return true;
        }
        return chargeResult.status() != BillStatus.UNPAID;
    }

    /**
     * Convert a payment type string to {@link PaymentMode}.
     * Default empty/null → CASH (most conservative: requires prepayment).
     */
    private static PaymentMode toPaymentMode(String paymentTypeStr) {
        if (paymentTypeStr == null || paymentTypeStr.isBlank()) {
            return PaymentMode.CASH;
        }
        try {
            return PaymentMode.valueOf(paymentTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PaymentMode.CASH;
        }
    }
}
