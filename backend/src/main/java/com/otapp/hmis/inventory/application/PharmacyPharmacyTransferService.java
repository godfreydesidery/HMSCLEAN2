package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.application.dto.PpRoDetailRequest;
import com.otapp.hmis.inventory.application.dto.PpRoRequest;
import com.otapp.hmis.inventory.application.dto.PpToBatchRequest;
import com.otapp.hmis.inventory.application.dto.TransferDto;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyBatch;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyBatchRepository;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyRO;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyRODetail;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyRORepository;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyRN;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyRNDetail;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyRNRepository;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyTO;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyTODetail;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyTODetailRepository;
import com.otapp.hmis.inventory.domain.PharmacyToPharmacyTORepository;
import com.otapp.hmis.inventory.domain.SpToStatus;
import com.otapp.hmis.masterdata.lookup.MedicineLookup;
import com.otapp.hmis.masterdata.lookup.PharmacyLookup;
import com.otapp.hmis.pharmacy.api.PharmacyStockCredit;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.documentnumber.DocumentNumberService;
import com.otapp.hmis.shared.documentnumber.DocumentType;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pharmacy↔Pharmacy transfer service (inc-08b chunk 7) — the 3-document chain (PPR→PPTO→PPRN).
 * Structurally identical to the store↔pharmacy transfer (chunk 6) with three behavioural differences:
 *
 * <ul>
 *   <li><b>1:1 quantities</b> — no coefficient (both sides are pharmacies, D9).</li>
 *   <li><b>SOURCE = delivering pharmacy</b>: TO.issue debits it via {@code pharmacy::api}
 *       {@code debitTransferOut} (hard gate + FEFO + TRANSFER_OUT card).</li>
 *   <li><b>DESTINATION batch GAP</b>: RN.complete credits the requesting pharmacy via
 *       {@code creditTransferAggregate} — aggregate + IN card ONLY, <b>NO destination batch</b>
 *       (the reproduced legacy gap, Q7). Contrast the store path which creates dest batches.</li>
 * </ul>
 *
 * <p>RN PENDING→COMPLETED guard lives in the entity/service (the double-post fix). PPR/PPTO/PPRN
 * numbering via the shared DocumentNumberService (PPTO replaces the legacy SPT collision, CR-10).
 */
@Service
@RequiredArgsConstructor
public class PharmacyPharmacyTransferService {

    private final PharmacyToPharmacyRORepository roRepository;
    private final PharmacyToPharmacyTORepository toRepository;
    private final PharmacyToPharmacyTODetailRepository toDetailRepository;
    private final PharmacyToPharmacyRNRepository rnRepository;
    private final PharmacyToPharmacyBatchRepository batchRepository;
    private final PharmacyStockCredit pharmacyStock;
    private final PharmacyLookup pharmacyLookup;
    private final MedicineLookup medicineLookup;
    private final DocumentNumberService documentNumberService;
    private final AuditRecorder auditRecorder;

    private static final String AUDIT_RO = "inventory.PharmacyToPharmacyRO";
    private static final String AUDIT_TO = "inventory.PharmacyToPharmacyTO";
    private static final String AUDIT_RN = "inventory.PharmacyToPharmacyRN";

    // ===================== RO (PPR) =====================

    @Transactional
    public TransferDto createRo(PpRoRequest req, TxAuditContext ctx) {
        if (!pharmacyLookup.existsByUid(req.requestingPharmacyUid())) {
            throw new NotFoundException("Requesting pharmacy not found: " + req.requestingPharmacyUid());
        }
        if (!pharmacyLookup.existsByUid(req.deliveringPharmacyUid())) {
            throw new NotFoundException("Delivering pharmacy not found: " + req.deliveringPharmacyUid());
        }
        String no = documentNumberService.next(DocumentType.PPR);
        // constructor enforces requesting != delivering
        PharmacyToPharmacyRO ro = new PharmacyToPharmacyRO(
                no, req.requestingPharmacyUid(), req.deliveringPharmacyUid(), ctx.dayUid());
        roRepository.save(ro);
        auditRecorder.record(AUDIT_RO, ro.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toPpRoDto(ro);
    }

    @Transactional
    public TransferDto addRoDetail(String roUid, PpRoDetailRequest req, TxAuditContext ctx) {
        PharmacyToPharmacyRO ro = requireRo(roUid);
        if (!medicineLookup.existsByUid(req.medicineUid())) {
            throw new NotFoundException("Medicine not found");
        }
        if (ro.getDetails().stream().anyMatch(d -> d.getMedicineUid().equals(req.medicineUid()))) {
            throw new InvalidPatientOperationException("Duplicates are not allowed");
        }
        ro.addDetail(new PharmacyToPharmacyRODetail(ro, req.medicineUid(), req.orderedQty()));
        auditRecorder.record(AUDIT_RO, ro.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toPpRoDto(ro);
    }

    @Transactional
    public TransferDto verifyRo(String roUid, TxAuditContext ctx) {
        PharmacyToPharmacyRO ro = requireRo(roUid);
        ro.verify();
        return auditedRo(ro, ctx);
    }

    @Transactional
    public TransferDto approveRo(String roUid, TxAuditContext ctx) {
        PharmacyToPharmacyRO ro = requireRo(roUid);
        ro.approve();
        return auditedRo(ro, ctx);
    }

    @Transactional
    public TransferDto submitRo(String roUid, TxAuditContext ctx) {
        PharmacyToPharmacyRO ro = requireRo(roUid);
        ro.submit();
        return auditedRo(ro, ctx);
    }

    // ===================== TO (PPTO) =====================

    @Transactional
    public TransferDto createToFromRo(String roUid, TxAuditContext ctx) {
        PharmacyToPharmacyRO ro = requireRo(roUid);
        if (toRepository.existsByPharmacyToPharmacyRO(ro)) {
            throw new InvalidPatientOperationException("A transfer order already exists for this requisition");
        }
        ro.markInProcess();             // SUBMITTED -> IN-PROCESS (guards SUBMITTED)
        String no = documentNumberService.next(DocumentType.PPTO);
        PharmacyToPharmacyTO to = new PharmacyToPharmacyTO(
                no, ro.getRequestingPharmacyUid(), ro.getDeliveringPharmacyUid(), ro, ctx.dayUid());
        ro.getDetails().forEach(d -> to.addDetail(new PharmacyToPharmacyTODetail(
                to, d.getMedicineUid(), d.getOrderedQty())));
        toRepository.save(to);
        auditRecorder.record(AUDIT_TO, to.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toPpToDto(to);
    }

    /** Add a batch to a PENDING TO detail (1:1 accumulate, cumulative ≤ ordered). */
    @Transactional
    public TransferDto addToBatch(String toDetailUid, PpToBatchRequest req, TxAuditContext ctx) {
        PharmacyToPharmacyTODetail detail = toDetailRepository.findByUid(toDetailUid)
                .orElseThrow(() -> new NotFoundException("Transfer order detail not found: " + toDetailUid));
        if (detail.getPharmacyToPharmacyTO().getStatus() != SpToStatus.PENDING) {
            throw new InvalidPatientOperationException("Only pending transfer orders can be edited");
        }
        if (!detail.getMedicineUid().equals(req.medicineUid())) {
            throw new InvalidPatientOperationException("Batch medicine must match the detail medicine");
        }
        detail.accumulate(req.qty());        // cumulative <= ordered
        batchRepository.save(new PharmacyToPharmacyBatch(
                detail, req.batchNo(), req.manufacturedDate(), req.expiryDate(), req.qty()));
        auditRecorder.record(AUDIT_TO, detail.getPharmacyToPharmacyTO().getUid(), AuditAction.UPDATE,
                ctx.actorUsername());
        return InventoryMapper.toPpToDto(detail.getPharmacyToPharmacyTO());
    }

    @Transactional
    public TransferDto verifyTo(String toUid, TxAuditContext ctx) {
        PharmacyToPharmacyTO to = requireTo(toUid);
        to.verify();
        return auditedTo(to, ctx);
    }

    @Transactional
    public TransferDto approveTo(String toUid, TxAuditContext ctx) {
        PharmacyToPharmacyTO to = requireTo(toUid);
        to.approve();
        return auditedTo(to, ctx);
    }

    /** Issue (APPROVED→GOODS-ISSUED): SOURCE (delivering) pharmacy stock decrements here. */
    @Transactional
    public TransferDto issueTo(String toUid, TxAuditContext ctx) {
        PharmacyToPharmacyTO to = requireTo(toUid);
        to.markGoodsIssued();                       // guards APPROVED
        String reference = "Goods transfered to Pharmacy PTPO# " + to.getNo();
        for (PharmacyToPharmacyTODetail d : to.getDetails()) {
            if (d.getTransferedQty().compareTo(BigDecimal.ZERO) > 0) {
                pharmacyStock.debitTransferOut(to.getDeliveringPharmacyUid(), d.getMedicineUid(),
                        d.getTransferedQty(), reference, ctx);
            }
        }
        if (to.getPharmacyToPharmacyRO() != null) {
            to.getPharmacyToPharmacyRO().markGoodsIssued();
        }
        return auditedTo(to, ctx);
    }

    // ===================== RN (PPRN) =====================

    @Transactional
    public TransferDto createRnFromTo(String toUid, TxAuditContext ctx) {
        PharmacyToPharmacyTO to = requireTo(toUid);
        if (to.getStatus() != SpToStatus.GOODS_ISSUED) {
            throw new InvalidPatientOperationException("Could not create/process GRN. Goods not issued");
        }
        if (rnRepository.existsByPharmacyToPharmacyTO(to)) {
            throw new InvalidPatientOperationException("A received note already exists for this transfer order");
        }
        String no = documentNumberService.next(DocumentType.PPRN);
        PharmacyToPharmacyRN rn = new PharmacyToPharmacyRN(
                no, to.getRequestingPharmacyUid(), to.getDeliveringPharmacyUid(), to, ctx.dayUid());
        to.getDetails().forEach(td -> rn.addDetail(new PharmacyToPharmacyRNDetail(
                rn, td.getMedicineUid(), td.getOrderedQty(), td.getTransferedQty())));  // receivedQty=transfered
        rnRepository.save(rn);
        // re-parent trace batches from TO detail onto the matching RN detail
        for (PharmacyToPharmacyTODetail td : to.getDetails()) {
            PharmacyToPharmacyRNDetail rd = rn.getDetails().stream()
                    .filter(x -> x.getMedicineUid().equals(td.getMedicineUid())).findFirst().orElseThrow();
            batchRepository.findAll().stream()
                    .filter(b -> b.getPharmacyToPharmacyTODetail() != null
                            && b.getPharmacyToPharmacyTODetail().getUid().equals(td.getUid()))
                    .forEach(b -> {
                        b.reparentToRn(rd);
                        rd.attachBatch(b);
                    });
        }
        auditRecorder.record(AUDIT_RN, rn.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toPpRnDto(rn);
    }

    /**
     * Complete the RN (PENDING→COMPLETED): DESTINATION (requesting) pharmacy stock increments —
     * AGGREGATE + IN card ONLY, NO destination batch (the reproduced Q7 gap). Flips RO + TO COMPLETED.
     */
    @Transactional
    public TransferDto completeRn(String rnUid, TxAuditContext ctx) {
        PharmacyToPharmacyRN rn = rnRepository.findByUid(rnUid)
                .orElseThrow(() -> new NotFoundException("Received note not found: " + rnUid));
        rn.complete();                              // guards PENDING (entity, not controller)
        String reference = "Medicine received # " + rn.getNo();
        for (PharmacyToPharmacyRNDetail rd : rn.getDetails()) {
            if (rd.getReceivedQty().compareTo(BigDecimal.ZERO) > 0) {
                // NO batch — the reproduced p2p destination-batch gap (Q7)
                pharmacyStock.creditTransferAggregate(
                        rn.getRequestingPharmacyUid(), rd.getMedicineUid(), rd.getReceivedQty(),
                        reference, ctx);
            }
        }
        PharmacyToPharmacyTO to = rn.getPharmacyToPharmacyTO();
        if (to != null) {
            to.markCompleted();
            if (to.getPharmacyToPharmacyRO() != null) {
                to.getPharmacyToPharmacyRO().markCompleted();
            }
        }
        auditRecorder.record(AUDIT_RN, rn.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toPpRnDto(rn);
    }

    // ===================== reads =====================

    @Transactional(readOnly = true)
    public TransferDto getRo(String uid) {
        return InventoryMapper.toPpRoDto(requireRo(uid));
    }

    @Transactional(readOnly = true)
    public TransferDto getTo(String uid) {
        return InventoryMapper.toPpToDto(requireTo(uid));
    }

    @Transactional(readOnly = true)
    public TransferDto getRn(String uid) {
        return InventoryMapper.toPpRnDto(rnRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Received note not found: " + uid)));
    }

    // ===================== helpers =====================

    private PharmacyToPharmacyRO requireRo(String uid) {
        return roRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Requisition not found: " + uid));
    }

    private PharmacyToPharmacyTO requireTo(String uid) {
        return toRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Transfer order not found: " + uid));
    }

    private TransferDto auditedRo(PharmacyToPharmacyRO ro, TxAuditContext ctx) {
        auditRecorder.record(AUDIT_RO, ro.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toPpRoDto(ro);
    }

    private TransferDto auditedTo(PharmacyToPharmacyTO to, TxAuditContext ctx) {
        auditRecorder.record(AUDIT_TO, to.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toPpToDto(to);
    }
}
