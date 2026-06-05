package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingQueries;
import com.otapp.hmis.clinical.api.DispenseConfirmation;
import com.otapp.hmis.clinical.api.PrescriptionBatchView;
import com.otapp.hmis.clinical.api.PrescriptionDispensePort;
import com.otapp.hmis.clinical.api.PrescriptionPatientType;
import com.otapp.hmis.clinical.api.PrescriptionReadPort;
import com.otapp.hmis.clinical.api.PrescriptionView;
import com.otapp.hmis.clinical.api.PrescriptionWorklistPort;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionBatch;
import com.otapp.hmis.clinical.domain.PrescriptionBatchRepository;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.PrescriptionStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of the published {@code clinical :: api} prescription seams
 * consumed by the {@code pharmacy} module (inc-08a chunk 1): {@link PrescriptionReadPort},
 * {@link PrescriptionWorklistPort}, {@link PrescriptionDispensePort}.
 *
 * <p>Mirrors the {@code ConsultationLookupImpl} pattern — package-private, delegating to the
 * intra-module domain repositories and the {@link Prescription} aggregate's own guards. The web
 * layer still uses {@link PrescriptionPort}; these are the CROSS-module contracts.
 *
 * <p><strong>Worklist FILTER (Q1, AC-RX-PRE-03/04):</strong> reproduces the verified legacy
 * bill-status filter — OUTPATIENT/OUTSIDER admit {@code PAID|COVERED}; INPATIENT additionally admits
 * {@code VERIFIED} — by delegating the three-valued decision to {@code billing :: api}
 * {@link BillingQueries#worklistAdmits(String, boolean)} (clinical already depends on
 * {@code billing :: api}; no new edge, no cycle). This SUPERSEDES the inc-05 intra-module
 * {@code pharmacyWorklist()} which deliberately returned all NOT-GIVEN (AC-RX-PRE-05 reconciliation).
 *
 * <p><strong>Dispense seam (Q1, AC-RX-PRE-06/07):</strong> {@link #markDispensed} runs in the
 * caller's (pharmacy) transaction (propagation REQUIRED) so the clinical NOT-GIVEN→GIVEN flip and the
 * pharmacy stock decrement commit atomically. NO bill-status re-check at this terminal.
 */
@Service
@RequiredArgsConstructor
class PrescriptionApiAdapter
        implements PrescriptionReadPort, PrescriptionWorklistPort, PrescriptionDispensePort {

    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionBatchRepository batchRepository;
    private final BillingQueries billingQueries;
    private final AuditRecorder auditRecorder;

    private static final String AUDIT_ENTITY = "clinical.Prescription";

    // =========================================================================
    // PrescriptionReadPort
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PrescriptionView getByUid(String prescriptionUid) {
        return toView(require(prescriptionUid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionBatchView> listBatches(String prescriptionUid) {
        Prescription p = require(prescriptionUid);
        return batchRepository.findByPrescriptionOrderByCreatedAtAsc(p).stream()
                .map(PrescriptionApiAdapter::toBatchView)
                .toList();
    }

    // =========================================================================
    // PrescriptionWorklistPort — THE FILTER (legacy bill-status filter, Q1)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionView> dispenseWorklist(WorklistFilter filter) {
        boolean inpatient = filter.patientType() == PrescriptionPatientType.INPATIENT;
        return prescriptionRepository
                .findByStatusOrderByCreatedAtAsc(PrescriptionStatus.NOT_GIVEN).stream()
                // scope to a single patient when requested
                .filter(p -> filter.patientUid() == null
                        || filter.patientUid().equals(p.getPatientUid()))
                // derive patient type from the encounter binding; only the requested type
                .filter(p -> patientTypeOf(p) == filter.patientType())
                // the legacy bill-status FILTER (PAID|COVERED; +VERIFIED for INPATIENT)
                .filter(p -> billingQueries.worklistAdmits(p.getPatientBillUid(), inpatient))
                .map(PrescriptionApiAdapter::toView)
                .toList();
    }

    // =========================================================================
    // PrescriptionDispensePort — the decrement seam (NOT-GIVEN -> GIVEN)
    // =========================================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public PrescriptionView markDispensed(String prescriptionUid, DispenseConfirmation cmd,
                                          TxAuditContext ctx) {
        Prescription p = require(prescriptionUid);
        // The aggregate enforces all four legacy guards + the NOT-GIVEN->GIVEN flip + approved_*.
        p.issue(cmd.issued(), cmd.issuePharmacyUid(),
                ctx.actorUsername(), ctx.dayUid(), Instant.now());

        // Persist clinical lot-trace (PrescriptionBatch) rows — no lot-trace is silently dropped (N9).
        if (cmd.lotTrace() != null) {
            for (DispenseConfirmation.LotTrace lot : cmd.lotTrace()) {
                batchRepository.save(new PrescriptionBatch(
                        p, lot.batchNo(), lot.manufacturedDate(), lot.expiryDate(), lot.qty()));
            }
        }

        auditRecorder.record(AUDIT_ENTITY, p.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return toView(p);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Prescription require(String uid) {
        return prescriptionRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Prescription not found: " + uid));
    }

    /** Derive the published patient-type discriminator from the encounter binding. */
    private static PrescriptionPatientType patientTypeOf(Prescription p) {
        if (p.getConsultation() != null) {
            return PrescriptionPatientType.OUTPATIENT;
        }
        if (p.getNonConsultation() != null) {
            return PrescriptionPatientType.OUTSIDER;
        }
        return PrescriptionPatientType.INPATIENT;   // admission-bound
    }

    private static PrescriptionView toView(Prescription p) {
        return new PrescriptionView(
                p.getUid(),
                p.getStatus().dbValue(),
                p.isSettled(),
                p.getMedicineUid(),
                p.getPatientUid(),
                p.getPatientBillUid(),
                p.getPaymentType(),
                p.getMembershipNo(),
                p.getInsurancePlanUid(),
                p.getClinicianUserUid(),
                p.getIssuePharmacyUid(),
                p.getConsultation() != null ? p.getConsultation().getUid() : null,
                p.getNonConsultation() != null ? p.getNonConsultation().getUid() : null,
                p.getAdmissionUid(),
                patientTypeOf(p),
                p.getQty(),
                p.getIssued(),
                p.getBalance(),
                p.getOrderedAt(),
                p.getApprovedAt());
    }

    private static PrescriptionBatchView toBatchView(PrescriptionBatch b) {
        return new PrescriptionBatchView(
                b.getUid(),
                b.getPrescription().getUid(),
                b.getNo(),
                b.getManufacturedDate(),
                b.getExpiryDate(),
                b.getQty(),
                b.getCreatedAt());
    }
}
