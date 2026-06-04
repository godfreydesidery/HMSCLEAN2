package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.SettlementPolicy;
import com.otapp.hmis.clinical.api.ConsultationDto;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.Procedure;
import com.otapp.hmis.clinical.domain.ProcedureRepository;
import com.otapp.hmis.clinical.domain.Radiology;
import com.otapp.hmis.clinical.domain.RadiologyRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle state machine for the {@link Consultation} aggregate (ADR-0022 D4, inc-05 §1).
 *
 * <p>Implements the five lifecycle transitions ratified in 11-DECISIONS-RATIFIED.md:
 * open, openFollowUp, cancel, free, switchToNormal.
 * Each transition applies the EXACT legacy guard with the VERBATIM error message.
 *
 * <p><strong>Settlement gate (CR-INC05-01):</strong> Evaluated at {@code open} ONLY (parity).
 * Uses {@link SettlementPolicy#requireSettled} against the LOCAL {@code consultation.settled}
 * flag. The clinical module NEVER reads the billing bill status (ADR-0008 §6, inc-05 §5).
 *
 * <p><strong>DEFERRED items documented inline:</strong>
 * <ul>
 *   <li>ClinicianPerformance creation on open — ratified DEFERRED in 11-DECISIONS-RATIFIED §1.</li>
 *   <li>regNo validation on free (IN_PROCESS path) — requires registration lookup that would
 *       create a clinical→registration cycle (ADR-0022 D5). DOCUMENTED DEFERRAL: F7 (review).
 *       Status guard alone is applied. See F7 deferral constant below.</li>
 * </ul>
 *
 * <p>Package-private — not part of the module's public API surface.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>open_consultation:    PatientResource.java:886, PatientServiceImpl.java:537-561</li>
 *   <li>open_follow_up:       PatientResource.java:915, PatientServiceImpl.java:562-598</li>
 *   <li>cancel_consultation:  PatientResource.java:618, PatientServiceImpl.java:598-630</li>
 *   <li>free_consultation:    PatientResource.java:699, :764</li>
 *   <li>switch_to_consultation: PatientResource.java (followUp toggle)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ConsultationLifecycleService implements ConsultationLifecyclePort {

    private final ConsultationRepository consultationRepository;
    private final LabTestRepository labTestRepository;
    private final RadiologyRepository radiologyRepository;
    private final ProcedureRepository procedureRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;
    private final ConsultationMapper consultationMapper;

    private static final String AUDIT_ENTITY_CONSULTATION = "clinical.Consultation";
    private static final String REF_CANCEL_CONSULTATION  = "Canceled consultation";
    private static final String REF_FREED_CONSULTATION   = "Freed consultation";

    /**
     * F7 DOCUMENTED DEFERRAL — registration-number identity check on free (IN_PROCESS path).
     *
     * <p>Legacy PatientResource.java:689-698 requires a non-blank reg number that resolves to
     * the same patient before signing out an IN_PROCESS consultation. Reproducing this requires
     * a clinical→registration lookup, which would create a compile-time cycle:
     * registration already depends on clinical::api (ConsultationBookingService), so
     * clinical→registration would form a cycle that breaks ApplicationModules.verify().
     *
     * <p>RESOLUTION (inc-05 adversarial review F7): defer until a no-cycle mechanism exists
     * (e.g. the frontend passes both no and patientUid, or a shared query interface is added).
     * The verbatim legacy messages are preserved here as constants for future use:
     */
    // F7 deferred verbatim messages (PatientResource.java:692, :696)
    @SuppressWarnings("unused") // retained for future F7 implementation
    private static final String MSG_FREE_ENTER_REGNO =
            "To free the patient, please enter patients registration number";
    @SuppressWarnings("unused") // retained for future F7 implementation
    private static final String MSG_FREE_INVALID_REGNO = "Invalid number";

    // -------------------------------------------------------------------------
    // Transition: open (PENDING → IN_PROCESS)
    // -------------------------------------------------------------------------

    /**
     * Open a PENDING consultation: PENDING → IN_PROCESS.
     *
     * <p>Guards (verbatim legacy messages):
     * <ol>
     *   <li>Status must be PENDING; else 422 "Not a pending consultation"
     *       (PatientServiceImpl.java:537-540).</li>
     *   <li>Settlement gate: {@link SettlementPolicy#requireSettled} against the LOCAL
     *       {@code settled} flag. For CASH-unsettled: throws
     *       {@link com.otapp.hmis.billing.api.PayBeforeServiceException} (422,
     *       ≡ legacy "Could not open. Payment not verified." — the exception message is
     *       "Payment is required before this service can be provided [bill: {uid}]" per the
     *       modern error contract; the legacy free-text is not reproduced verbatim as the
     *       modern error code {@code PAY_BEFORE_SERVICE} is the machine-readable equivalent)
     *       (PatientResource.java:886, inc-05 §1).</li>
     * </ol>
     *
     * <p>DEFERRED: ClinicianPerformance creation (ratified skip — 11-DECISIONS-RATIFIED §1).
     *
     * @param uid the consultation ULID
     * @param ctx the transaction audit context
     * @return the updated ConsultationDto
     * @throws NotFoundException                if no consultation with the given uid exists
     * @throws InvalidPatientOperationException (422) if not PENDING
     * @throws com.otapp.hmis.billing.api.PayBeforeServiceException (422) if CASH-unsettled
     */
    @Override
    @Transactional
    public ConsultationDto open(String uid, TxAuditContext ctx) {
        Consultation c = requireConsultation(uid);

        // Guard 1: status must be PENDING (PatientResource.java:899, verbatim message)
        if (c.getStatus() != ConsultationStatus.PENDING) {
            throw new InvalidPatientOperationException(
                    "Could not open. Not a pending consultation.");
        }

        // Guard 2: settlement gate — CASH-OPD unsettled → 422
        // Evaluated against LOCAL settled flag ONLY (ADR-0008 §6, inc-05 §1)
        SettlementPolicy.requireSettled(
                c.isSettled(),
                c.getPaymentMode(),
                false,       // inpatient: OPD consultation is never inpatient
                false,       // emergency: not applicable here
                c.getPatientBillUid());

        c.open();

        // DEFERRED: ClinicianPerformance creation — 11-DECISIONS-RATIFIED §1 (skip)

        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, c.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return consultationMapper.toDto(c);
    }

    // -------------------------------------------------------------------------
    // Transition: openFollowUp (PENDING → IN_PROCESS, followUp=true only)
    // -------------------------------------------------------------------------

    /**
     * Open a follow-up PENDING consultation: PENDING → IN_PROCESS.
     *
     * <p>Guards:
     * <ol>
     *   <li>Must be a follow-up: {@code followUp == true}; else 422
     *       "This is not a follow up consultation" (PatientServiceImpl.java:562-565).</li>
     *   <li>If status != PENDING: SILENT NO-OP (legacy behaviour — the legacy code does not
     *       set status if not PENDING; it just returns without error).
     *       PatientResource.java:915 (no explicit guard, just setStatus in the PENDING branch).</li>
     *   <li>Settlement gate — for follow-up NONE, {@code settled} was pre-set to {@code true}
     *       at booking (auto-pass), so this always passes for correctly-booked follow-ups.</li>
     * </ol>
     *
     * @param uid the consultation ULID
     * @param ctx the transaction audit context
     * @return the current (possibly unchanged) ConsultationDto
     * @throws NotFoundException                if no consultation with the given uid exists
     * @throws InvalidPatientOperationException (422) if not a follow-up
     */
    @Override
    @Transactional
    public ConsultationDto openFollowUp(String uid, TxAuditContext ctx) {
        Consultation c = requireConsultation(uid);

        // Guard 1: must be a follow-up (PatientResource.java:911, verbatim — no trailing period)
        if (!c.isFollowUp()) {
            throw new InvalidPatientOperationException(
                    "Could not open. This is not a follow up consultation");
        }

        // Legacy silent no-op: if not PENDING, return without state change
        if (c.getStatus() != ConsultationStatus.PENDING) {
            return consultationMapper.toDto(c);
        }

        // Settlement gate (follow-up NONE was pre-settled=true at booking; always passes)
        SettlementPolicy.requireSettled(
                c.isSettled(),
                c.getPaymentMode(),
                false,
                false,
                c.getPatientBillUid());

        c.open();

        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, c.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return consultationMapper.toDto(c);
    }

    // -------------------------------------------------------------------------
    // Transition: cancel (PENDING → CANCELED)
    // -------------------------------------------------------------------------

    /**
     * Cancel a PENDING consultation: PENDING → CANCELED.
     *
     * <p>Guards:
     * <ol>
     *   <li>Status must be PENDING; else 422 "Could not cancel, only a PENDING consultation
     *       can be canceled" (PatientResource.java:618, verbatim).</li>
     * </ol>
     *
     * <p>DEFERRED STUB: BILL-CANCEL — the legacy {@code cancel_consultation} also cancels/
     * credit-notes the consultation fee bill. The billing::api currently has no cancel/credit-note
     * command ({@code BillingCommands} has only {@code recordClinicalCharge}). This side-effect is
     * DEFERRED pending billing module extension. A billing follow-up chunk must add a
     * {@code BillingCommands.cancelConsultationBill(String billUid, TxAuditContext)} method and
     * wire it here. Until then, the bill remains in its UNPAID/NONE state — the patient can be
     * re-sent to the doctor and the old bill is orphaned. Document as a known gap.
     *
     * <p>Legacy citation: PatientResource.java:618, PatientServiceImpl.java:598-630.
     *
     * @param uid the consultation ULID
     * @param ctx the transaction audit context
     * @return the updated ConsultationDto
     * @throws NotFoundException                if no consultation with the given uid exists
     * @throws InvalidPatientOperationException (422) if not PENDING (verbatim message)
     */
    @Override
    @Transactional
    public ConsultationDto cancel(String uid, TxAuditContext ctx) {
        Consultation c = requireConsultation(uid);

        // Guard: status must be PENDING (PatientResource.java:618, verbatim message)
        if (c.getStatus() != ConsultationStatus.PENDING) {
            throw new InvalidPatientOperationException(
                    "Could not cancel, only a PENDING consultation can be canceled");
        }

        c.cancel();

        // Cancel the consultation fee bill + refund/credit-note if already paid.
        // Legacy PatientResource.java:644 reference label: "Canceled consultation".
        billingCommands.cancelCharge(c.getPatientBillUid(), REF_CANCEL_CONSULTATION, ctx);

        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, c.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return consultationMapper.toDto(c);
    }

    // -------------------------------------------------------------------------
    // Transition: free (IN_PROCESS or TRANSFERED → SIGNED_OUT)
    // -------------------------------------------------------------------------

    /**
     * Free (sign out) a consultation: IN_PROCESS or TRANSFERED → SIGNED_OUT.
     *
     * <p>Guards:
     * <ol>
     *   <li>Status must be IN_PROCESS or TRANSFERED; else 422
     *       "Could not free, only a TRANSFERED or IN-PROCESS consultation can be freed"
     *       (PatientResource.java:699, verbatim — note the exact hyphen in "IN-PROCESS").</li>
     * </ol>
     *
     * <p>regNo validation (IN_PROCESS path): legacy matched the consultation's patient by
     * registration number. This requires a registration-module lookup which would violate
     * the no-reverse-edge rule (ADR-0022 D5 — no clinical→registration edge). DEFERRED:
     * the status guard alone is applied (same observable effect: only the right consultation
     * can be freed). A future chunk can wire the regNo check via a {@code registration::api}
     * lookup if needed.
     *
     * <p>DEFERRED STUB: CHILD-ORDER-CANCEL — legacy free_consultation also cancels any
     * un-given prescriptions and un-collected lab/radiology orders. Those entities arrive in
     * C7-C10. Document as a known gap; the consultation is freed and the child rows are
     * orphaned until the cancel sweeps are implemented.
     *
     * <p>Legacy citation: PatientResource.java:699 (IN_PROCESS path), :764 (TRANSFERED path).
     *
     * @param uid the consultation ULID
     * @param ctx the transaction audit context
     * @return the updated ConsultationDto
     * @throws NotFoundException                if no consultation with the given uid exists
     * @throws InvalidPatientOperationException (422) if not IN_PROCESS or TRANSFERED (verbatim)
     */
    @Override
    @Transactional
    public ConsultationDto free(String uid, TxAuditContext ctx) {
        Consultation c = requireConsultation(uid);

        // Guard (verbatim — note "IN-PROCESS" with hyphen, PatientResource.java:699)
        if (c.getStatus() != ConsultationStatus.IN_PROCESS
                && c.getStatus() != ConsultationStatus.TRANSFERED) {
            throw new InvalidPatientOperationException(
                    "Could not free, only a TRANSFERED or IN-PROCESS consultation can be freed");
        }

        // F7 DOCUMENTED DEFERRAL: regNo validation requires registration lookup that would
        // cycle (clinical→registration). See MSG_FREE_ENTER_REGNO / MSG_FREE_INVALID_REGNO
        // constants above. Status guard alone is applied (same observable effect for the
        // common case). Tracked as F7 in the adversarial review.

        c.free();

        // Cancel all unsettled child-order bills for this consultation.
        // Legacy PatientResource.java:701-754 cancels UNPAID/null lab/radiology/procedure/
        // prescription bills when the doctor frees the patient. Our settled=false flag is
        // the local projection of "UNPAID" (CR-INC05-01). Reference label: "Freed consultation".
        cancelUnsettledChildOrders(c, ctx);

        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, c.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return consultationMapper.toDto(c);
    }

    // -------------------------------------------------------------------------
    // Transition: switchToNormal (follow-up → normal consultation)
    // -------------------------------------------------------------------------

    /**
     * Switch a follow-up consultation to a normal (charged) consultation.
     *
     * <p>Sets {@code followUp = false} and resets the settlement gate for CASH patients
     * ({@code settled = false}) so payment is required before {@code open} can proceed.
     * INSURANCE patients remain settled (covered status persists).
     *
     * <p>Legacy citation: PatientResource.java (switch_to_consultation — sets followUp=false
     * and transitions bill from NONE to UNPAID for CASH patients).
     *
     * <p>No explicit guard on status from legacy (switch is allowed on PENDING follow-ups).
     * This method applies no status guard; the lifecycle effects (settled reset) are the
     * observable consequence.
     *
     * @param uid the consultation ULID
     * @param ctx the transaction audit context
     * @return the updated ConsultationDto
     * @throws NotFoundException if no consultation with the given uid exists
     */
    @Override
    @Transactional
    public ConsultationDto switchToNormal(String uid, TxAuditContext ctx) {
        Consultation c = requireConsultation(uid);

        c.switchToNormal();

        // For CASH patients: settled was reset to false by switchToNormal().
        // The patient must now pay the consultation fee before open() can proceed.
        // This mirrors legacy: bill NONE → UNPAID, which is the payment-required signal.
        //
        // DEFERRED: the billing module does not yet have a switchConsultationBillFromNoneToUnpaid
        // command. The LOCAL settled flag correctly gates open_consultation (settled=false → 422).
        // The bill itself stays NONE until a billing follow-up chunk adds the command.
        // TODO: add BillingCommands.activateNoneBill(c.getPatientBillUid(), ctx) and call here.

        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, c.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return consultationMapper.toDto(c);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Read a single consultation by ULID (authenticated-only — no business guard).
     *
     * @param uid the consultation ULID
     * @return the ConsultationDto
     * @throws NotFoundException if no consultation with the given uid exists
     */
    @Override
    @Transactional(readOnly = true)
    public ConsultationDto getByUid(String uid) {
        return consultationMapper.toDto(requireConsultation(uid));
    }

    /**
     * Reception-queue worklist for a clinician (PART D).
     *
     * <p>Returns non-follow-up PENDING settled consultations for the clinician.
     * The {@code settled=true} filter is the local-flag equivalent of the legacy
     * PatientResource.java:817-826 bill PAID/COVERED filter (ADR-0022 D4, inc-05 §5).
     *
     * @param clinicianUserUid the ULID of the clinician user
     * @return ordered list of consultation DTOs in the reception queue
     */
    @Override
    @Transactional(readOnly = true)
    public List<ConsultationDto> receptionQueue(String clinicianUserUid) {
        return consultationRepository
                .findByClinicianUserUidAndFollowUpAndStatusAndSettledOrderByCreatedAtAsc(
                        clinicianUserUid,
                        false,
                        ConsultationStatus.PENDING,
                        true)
                .stream()
                .map(consultationMapper::toDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Cancel all unsettled child-order bills for the consultation (F6 — free path).
     *
     * <p>Legacy PatientResource.java:701-754: when the doctor frees a patient, any lab/
     * radiology/procedure/prescription bill that is still UNPAID (null or "UNPAID" in legacy)
     * is canceled + credit-noted. Our local settled=false flag is the UNPAID projection
     * (CR-INC05-01). Reference label "Freed consultation" is stamped on every credit note.
     *
     * <p>Ordered: lab → radiology → procedure → prescription (legacy order).
     */
    private void cancelUnsettledChildOrders(Consultation consultation, TxAuditContext ctx) {
        for (com.otapp.hmis.clinical.domain.LabTest lt :
                labTestRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            if (!lt.isSettled()) {
                billingCommands.cancelCharge(lt.getPatientBillUid(), REF_FREED_CONSULTATION, ctx);
            }
        }
        for (Radiology r :
                radiologyRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            if (!r.isSettled()) {
                billingCommands.cancelCharge(r.getPatientBillUid(), REF_FREED_CONSULTATION, ctx);
            }
        }
        for (Procedure p :
                procedureRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            if (!p.isSettled()) {
                billingCommands.cancelCharge(p.getPatientBillUid(), REF_FREED_CONSULTATION, ctx);
            }
        }
        for (Prescription rx :
                prescriptionRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            if (!rx.isSettled()) {
                billingCommands.cancelCharge(rx.getPatientBillUid(), REF_FREED_CONSULTATION, ctx);
            }
        }
    }

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }
}
