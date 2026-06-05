package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.api.ConsumableChartPort;
import com.otapp.hmis.clinical.api.ConsumableChartView;
import com.otapp.hmis.clinical.api.RecordConsumableChartCommand;
import com.otapp.hmis.clinical.domain.PatientConsumableChart;
import com.otapp.hmis.clinical.domain.PatientConsumableChartRepository;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link ConsumableChartPort} (inc-07 07c-i, ADR-0008 §1).
 *
 * <p>This class is intentionally package-private in {@code clinical.application} — callers
 * depend only on the {@link ConsumableChartPort} interface from {@code clinical.api}.
 *
 * <p>Runs in the caller's transaction ({@code Propagation.REQUIRED}, no @Async /
 * REQUIRES_NEW). All clinical-side guards are enforced here verbatim.
 *
 * <p><strong>Guard responsibility split (build spec §3.4):</strong>
 * <ul>
 *   <li>INPATIENT-SIDE (before calling this port): admission-IN-PROCESS gate (verbatim messages);
 *       consumable-registered guard (ConsumableLookup.isConsumable — masterdata::lookup);
 *       qty &gt; 0 guard; medicine-exists guard (MedicineLookup).</li>
 *   <li>CLINICAL-SIDE (here): nurse-uid present guard ("Nurse information not found");
 *       24-hour delete window guard (verbatim legacy message).</li>
 * </ul>
 *
 * <p><strong>Billing (CR-07-Q13-billing-display APPROVED):</strong>
 * {@code kind=MEDICINE}, {@code billItem="Medication"}, {@code description="Consumable: <name>"}.
 * This reproduces the legacy display literals at PatientServiceImpl.java:2312-2313.
 *
 * <p><strong>CR-07-Q11 FIX #1 (invoiceDetail.qty = chart.qty):</strong>
 * The as-built {@code PatientInvoiceDetail} constructor uses {@code bill.getQty()} — the
 * bill is created with {@code qty = chargeRequest.qty()} from the command. Therefore
 * {@code invoiceDetail.qty == chart.qty} is already satisfied. The legacy bug (hard-coded
 * {@code setQty(1)} at PatientServiceImpl.java:2372/2388/2441/2457) does NOT exist in the
 * as-built billing engine.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Save: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart)</li>
 *   <li>Delete guard: PatientResource.java:3040-3043</li>
 * </ul>
 *
 * <p>inc-07 07c-i / CR-07-consumable-stock / CR-07-Q11 / CR-07-Q13-billing-display.
 */
@Service
@RequiredArgsConstructor
class ConsumableChartPortImpl implements ConsumableChartPort {

    private static final Logger log = LoggerFactory.getLogger(ConsumableChartPortImpl.class);

    private static final String AUDIT_ENTITY = "clinical.PatientConsumableChart";

    /** Verbatim legacy message — PatientServiceImpl.java:2263-2265. */
    private static final String MSG_NO_NURSE = "Nurse information not found";

    /** Verbatim legacy message — PatientResource.java:3040-3043. */
    private static final String MSG_DELETE_24H =
            "Could not delete record. only records not exceeding 24 hours can be deleted";

    private final PatientConsumableChartRepository consumableChartRepository;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;

    // =========================================================================
    // Record
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Billing trichotomy (PatientServiceImpl.java:2307-2465):
     * <ol>
     *   <li>INSURANCE + MedicineInsurancePlan covered=true →
     *       {@code recordClinicalCharge(kind=MEDICINE, paymentType=INSURANCE)} →
     *       COVERED bill under PENDING PatientInvoice.</li>
     *   <li>Admission present + no covered plan (a.isPresent() == true at :2396) →
     *       billing engine emits VERIFIED bill under null-plan PENDING PatientInvoice.
     *       (Legacy condition at :2321: paymentType==INSURANCE || a.isPresent()==true → try plan;
     *       if no plan hit and a.isPresent() → VERIFIED path at :2396-2464.)</li>
     *   <li>Cash outpatient (paymentType=CASH, not inpatient) → UNPAID (no invoice). Note:
     *       the legacy savePatientConsumableChart only accepts inpatient context (:2290-2299),
     *       so in practice ONLY paths 1 and 2 occur for this method. The billing engine
     *       handles both via inpatient=true.</li>
     * </ol>
     *
     * <p><strong>CR-07-Q13-billing-display APPROVED (PatientServiceImpl.java:2312-2313):</strong>
     * billItem="Medication" / description="Consumable: <medicineName>".
     *
     * <p><strong>CR-07-Q11 FIX #1 confirmed:</strong>
     * BillingChargeService creates the PatientBill with qty from ChargeRequest.qty();
     * PatientInvoiceDetail constructor copies bill.getQty(). No hard-coded qty=1 in the
     * as-built billing engine. Anti-regression comment: do NOT reintroduce setQty(1).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ConsumableChartView recordConsumableChart(RecordConsumableChartCommand cmd,
                                                     TxAuditContext ctx) {
        // Clinical-side nurse guard (PatientServiceImpl.java:2263-2265)
        if (cmd.nurseUid() == null || cmd.nurseUid().isBlank()) {
            throw new InvalidPatientOperationException(MSG_NO_NURSE);
        }

        boolean isInsurance = "INSURANCE".equalsIgnoreCase(cmd.paymentType())
                && cmd.insurancePlanUid() != null && !cmd.insurancePlanUid().isBlank();
        boolean isInpatient = cmd.admissionUid() != null && !cmd.admissionUid().isBlank();

        PaymentMode paymentMode = isInsurance ? PaymentMode.INSURANCE : PaymentMode.CASH;

        // CR-07-Q13-billing-display APPROVED:
        // billItem="Medication" (PatientServiceImpl.java:2312)
        // description="Consumable: <medicineName>" (PatientServiceImpl.java:2313)
        // CR-07-Q11 FIX #1: qty passed as cmd.qty() so invoiceDetail.qty = chart.qty
        // (fixes legacy hard-coded setQty(1) at PatientServiceImpl.java:2372/2388/2441/2457
        //  — do NOT reintroduce the bug; anti-regression per CR-07-Q11)
        ChargeRequest chargeRequest = new ChargeRequest(
                cmd.patientUid(),
                isInsurance ? cmd.insurancePlanUid() : null,
                isInsurance ? cmd.membershipNo() : null,
                ServiceKind.MEDICINE,
                cmd.medicineUid(),
                cmd.qty(),            // FIX: chart.qty (not hard-coded 1) — CR-07-Q11 FIX #1
                paymentMode,
                isInpatient,
                false,                // followUp — not applicable for medicine charges
                "Medication",                         // billItem — PatientServiceImpl.java:2312
                "Consumable: " + cmd.medicineName(), // description — PatientServiceImpl.java:2313
                cmd.admissionUid()    // link bill to admission for discharge gate
        );

        var chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        PatientConsumableChart chart = PatientConsumableChart.create(
                cmd.qty(),
                cmd.paymentType(),
                isInsurance ? cmd.membershipNo() : null,
                chargeResult.billUid(),
                cmd.medicineUid(),
                cmd.patientUid(),
                cmd.admissionUid(),
                cmd.nurseUid(),
                isInsurance ? cmd.insurancePlanUid() : null,
                cmd.pharmacyUid());

        consumableChartRepository.save(chart);
        auditRecorder.record(AUDIT_ENTITY, chart.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        log.debug("ConsumableChartPortImpl: saved ConsumableChart uid={} billUid={} status={}",
                chart.getUid(), chargeResult.billUid(), chargeResult.status());
        return toView(chart);
    }

    // =========================================================================
    // Read
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<ConsumableChartView> findConsumableChartsByAdmission(String admissionUid) {
        return consumableChartRepository
                .findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ConsumableChartView findByUid(String chartUid) {
        return consumableChartRepository.findByUid(chartUid)
                .map(this::toView)
                .orElseThrow(() -> new NotFoundException("ConsumableChart not found: " + chartUid));
    }

    // =========================================================================
    // Delete (24h window — chart row only; billing reversal handled by caller)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Enforces the 24-hour window guard (PatientResource.java:3040-3043).
     * The billing reversal ({@code BillingCommands.cancelCharge("Canceled consumable", ...)})
     * is called by the inpatient orchestrator ({@code ConsumableChartService}) BEFORE this
     * method — this method only deletes the chart row.
     *
     * <p>CR-07-Q11 FIX #2: the credit-note reference used by the caller is "Canceled consumable"
     * (not the legacy mislabeled "Canceled lab test" at PatientResource.java:3053).
     * Anti-regression per CR-07-Q11: do NOT reuse "Canceled lab test" here.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteConsumableChart24h(String chartUid, TxAuditContext ctx) {
        PatientConsumableChart chart = consumableChartRepository.findByUid(chartUid)
                .orElseThrow(() -> new NotFoundException("ConsumableChart not found: " + chartUid));
        // 24h guard — verbatim PatientResource.java:3040-3043
        long hours = ChronoUnit.HOURS.between(chart.getCreatedAt(), ctx.timestamp());
        if (hours >= 24) {
            throw new InvalidPatientOperationException(MSG_DELETE_24H);
        }
        consumableChartRepository.delete(chart);
        auditRecorder.record(AUDIT_ENTITY, chartUid, AuditAction.DELETE, ctx.actorUsername());
    }

    // =========================================================================
    // Mapping
    // =========================================================================

    private ConsumableChartView toView(PatientConsumableChart c) {
        return new ConsumableChartView(
                c.getUid(),
                c.getAdmissionUid(),
                c.getNurseUid(),
                c.getMedicineUid(),
                c.getPharmacyUid(),
                c.getPatientBillUid(),
                c.getQty(),
                c.getStatus(),
                c.getPaymentType(),
                c.getCreatedAt());
    }
}
