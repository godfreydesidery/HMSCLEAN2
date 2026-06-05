package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.application.dto.PsRoDetailRequest;
import com.otapp.hmis.inventory.application.dto.PsRoRequest;
import com.otapp.hmis.inventory.application.dto.ToBatchRequest;
import com.otapp.hmis.inventory.application.dto.TransferDto;
import com.otapp.hmis.inventory.domain.PharmacyToStoreRO;
import com.otapp.hmis.inventory.domain.PharmacyToStoreRODetail;
import com.otapp.hmis.inventory.domain.PharmacyToStoreRORepository;
import com.otapp.hmis.inventory.domain.StoreMovementType;
import com.otapp.hmis.inventory.domain.StoreToPharmacyBatch;
import com.otapp.hmis.inventory.domain.StoreToPharmacyBatchRepository;
import com.otapp.hmis.inventory.domain.StoreToPharmacyRN;
import com.otapp.hmis.inventory.domain.StoreToPharmacyRNDetail;
import com.otapp.hmis.inventory.domain.StoreToPharmacyRNRepository;
import com.otapp.hmis.inventory.domain.StoreToPharmacyTO;
import com.otapp.hmis.inventory.domain.StoreToPharmacyTODetail;
import com.otapp.hmis.inventory.domain.StoreToPharmacyTODetailRepository;
import com.otapp.hmis.inventory.domain.StoreToPharmacyTORepository;
import com.otapp.hmis.masterdata.lookup.CoefficientLookup;
import com.otapp.hmis.masterdata.lookup.MedicineLookup;
import com.otapp.hmis.masterdata.lookup.PharmacyLookup;
import com.otapp.hmis.masterdata.lookup.StoreLookup;
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
 * Pharmacy↔Store transfer service (inc-08b chunk 6) — the full 3-document chain
 * (PSR RO → SPTO TO → PGRN RN), verbatim legacy stock-posting timing:
 *
 * <ul>
 *   <li><b>RO</b> moves NO stock (request only).</li>
 *   <li><b>TO.issue()</b> (APPROVED→GOODS-ISSUED) is where STORE stock decrements: hard
 *       negative-stock gate, decrement {@code StoreItem.stock}, OUT card, FEFO over StoreItemBatch
 *       (StoreToPharmacyTOServiceImpl.java:221-289). Also flips the RO to GOODS-ISSUED.</li>
 *   <li><b>RN.complete()</b> (PENDING→COMPLETED) is where PHARMACY stock increments: via
 *       {@code pharmacy::api} {@link PharmacyStockCredit} per re-parented batch (creates the
 *       destination PharmacyMedicineBatch + IN card). Also flips RO + TO to COMPLETED. <b>The
 *       PENDING→COMPLETED guard lives in the service/entity, not the controller</b> (the latent
 *       double-post fix).</li>
 * </ul>
 *
 * <p>Conversion {@code pharmacySKUQty = storeSKUQty * coefficient} happens on TO add_batch (full
 * BigDecimal precision; hard-fail if no coefficient). SPTO/PGRN numbering via DocumentNumberService;
 * PSR server-assigned (concurrency-safe per ADR-0009 §5).
 */
@Service
@RequiredArgsConstructor
public class PharmacyStoreTransferService {

    private final PharmacyToStoreRORepository roRepository;
    private final StoreToPharmacyTORepository toRepository;
    private final StoreToPharmacyTODetailRepository toDetailRepository;
    private final StoreToPharmacyRNRepository rnRepository;
    private final StoreToPharmacyBatchRepository batchRepository;
    private final StoreStockService storeStockService;
    private final PharmacyStockCredit pharmacyStockCredit;
    private final PharmacyLookup pharmacyLookup;
    private final StoreLookup storeLookup;
    private final MedicineLookup medicineLookup;
    private final CoefficientLookup coefficientLookup;
    private final DocumentNumberService documentNumberService;
    private final AuditRecorder auditRecorder;

    private static final String AUDIT_RO = "inventory.PharmacyToStoreRO";
    private static final String AUDIT_TO = "inventory.StoreToPharmacyTO";
    private static final String AUDIT_RN = "inventory.StoreToPharmacyRN";

    // ===================== RO (PSR) =====================

    @Transactional
    public TransferDto createRo(PsRoRequest req, TxAuditContext ctx) {
        if (!pharmacyLookup.existsByUid(req.pharmacyUid())) {
            throw new NotFoundException("Pharmacy not found: " + req.pharmacyUid());
        }
        if (!storeLookup.existsByUid(req.storeUid())) {
            throw new NotFoundException("Store not found: " + req.storeUid());
        }
        String no = documentNumberService.next(DocumentType.PSR);
        PharmacyToStoreRO ro = new PharmacyToStoreRO(no, req.pharmacyUid(), req.storeUid(), ctx.dayUid());
        roRepository.save(ro);
        auditRecorder.record(AUDIT_RO, ro.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toRoDto(ro);
    }

    @Transactional
    public TransferDto addRoDetail(String roUid, PsRoDetailRequest req, TxAuditContext ctx) {
        PharmacyToStoreRO ro = requireRo(roUid);
        if (!medicineLookup.existsByUid(req.medicineUid())) {
            throw new NotFoundException("Medicine not found");
        }
        boolean dup = ro.getDetails().stream().anyMatch(d -> d.getMedicineUid().equals(req.medicineUid()));
        if (dup) {
            throw new InvalidPatientOperationException("Duplicates items are not allowed");
        }
        ro.addDetail(new PharmacyToStoreRODetail(ro, req.medicineUid(), req.orderedQty()));
        auditRecorder.record(AUDIT_RO, ro.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toRoDto(ro);
    }

    @Transactional
    public TransferDto verifyRo(String roUid, TxAuditContext ctx) {
        PharmacyToStoreRO ro = requireRo(roUid);
        ro.verify();
        return auditedRo(ro, ctx);
    }

    @Transactional
    public TransferDto approveRo(String roUid, TxAuditContext ctx) {
        PharmacyToStoreRO ro = requireRo(roUid);
        ro.approve();
        return auditedRo(ro, ctx);
    }

    @Transactional
    public TransferDto submitRo(String roUid, TxAuditContext ctx) {
        PharmacyToStoreRO ro = requireRo(roUid);
        ro.submit();
        return auditedRo(ro, ctx);
    }

    // ===================== TO (SPTO) =====================

    /** Create the store's TO from a SUBMITTED RO (flips RO → IN-PROCESS). */
    @Transactional
    public TransferDto createToFromRo(String roUid, TxAuditContext ctx) {
        PharmacyToStoreRO ro = requireRo(roUid);
        if (toRepository.existsByPharmacyToStoreRO(ro)) {
            throw new InvalidPatientOperationException("A transfer order already exists for this requisition");
        }
        ro.markInProcess();             // SUBMITTED -> IN-PROCESS (guards SUBMITTED)
        String no = documentNumberService.next(DocumentType.SPTO);
        StoreToPharmacyTO to = new StoreToPharmacyTO(
                no, ro.getStoreUid(), ro.getPharmacyUid(), ro, ctx.dayUid());
        // seed one TO detail per RO line; the store item (itemUid) is set on the first add_batch
        ro.getDetails().forEach(d -> to.addDetail(new StoreToPharmacyTODetail(
                to, d.getMedicineUid(), d.getOrderedQty())));
        toRepository.save(to);
        auditRecorder.record(AUDIT_TO, to.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toToDto(to);
    }

    /**
     * Add a batch to a TO detail (PENDING TO): converts {@code pharmacySKUQty = storeSKUQty *
     * coefficient} (hard-fail if absent), accumulates the detail running totals (cumulative ≤ ordered),
     * and records the transfer batch (becomes the destination pharmacy batch at RN).
     */
    @Transactional
    public TransferDto addToBatch(String toDetailUid, ToBatchRequest req, TxAuditContext ctx) {
        StoreToPharmacyTODetail detail = toDetailRepository.findByUid(toDetailUid)
                .orElseThrow(() -> new NotFoundException("Transfer order detail not found: " + toDetailUid));
        if (detail.getStoreToPharmacyTO().getStatus()
                != com.otapp.hmis.inventory.domain.SpToStatus.PENDING) {
            throw new InvalidPatientOperationException("Only pending transfer orders can be edited");
        }
        BigDecimal coefficient = coefficientLookup.coefficientFor(req.itemUid(), req.medicineUid())
                .orElseThrow(() -> new NotFoundException("No conversion coefficient for this item/medicine"));
        BigDecimal pharmacySkuQty = req.storeSkuQty().multiply(coefficient);   // full precision
        detail.accumulate(req.itemUid(), req.storeSkuQty(), pharmacySkuQty);   // sets item; cumulative <= ordered
        StoreToPharmacyBatch batch = new StoreToPharmacyBatch(
                detail, req.batchNo(), req.manufacturedDate(), req.expiryDate(),
                req.storeSkuQty(), pharmacySkuQty);
        batchRepository.save(batch);
        auditRecorder.record(AUDIT_TO, detail.getStoreToPharmacyTO().getUid(), AuditAction.UPDATE,
                ctx.actorUsername());
        return InventoryMapper.toToDto(detail.getStoreToPharmacyTO());
    }

    @Transactional
    public TransferDto verifyTo(String toUid, TxAuditContext ctx) {
        StoreToPharmacyTO to = requireTo(toUid);
        to.verify();
        return auditedTo(to, ctx);
    }

    @Transactional
    public TransferDto approveTo(String toUid, TxAuditContext ctx) {
        StoreToPharmacyTO to = requireTo(toUid);
        to.approve();
        return auditedTo(to, ctx);
    }

    /**
     * Issue the TO (APPROVED→GOODS-ISSUED): STORE stock decrements here. Per detail with
     * transferedStoreSKUQty>0: decrementFefo on the store (hard negative-stock gate + FEFO + OUT
     * card). Flips the source RO to GOODS-ISSUED.
     */
    @Transactional
    public TransferDto issueTo(String toUid, TxAuditContext ctx) {
        StoreToPharmacyTO to = requireTo(toUid);
        to.markGoodsIssued();                            // guards APPROVED
        String reference = "Goods transfered to Pharmacy STPO# " + to.getNo();
        for (StoreToPharmacyTODetail d : to.getDetails()) {
            if (d.getTransferedStoreSkuQty().compareTo(BigDecimal.ZERO) > 0) {
                storeStockService.decrementFefo(to.getStoreUid(), d.getItemUid(),
                        d.getTransferedStoreSkuQty(), StoreMovementType.TRANSFER_OUT, reference, ctx);
            }
        }
        if (to.getPharmacyToStoreRO() != null) {
            to.getPharmacyToStoreRO().markGoodsIssued();
        }
        return auditedTo(to, ctx);
    }

    // ===================== RN (PGRN) =====================

    /** Create the pharmacy's RN from a GOODS-ISSUED TO (snapshots quantities + re-parents batches). */
    @Transactional
    public TransferDto createRnFromTo(String toUid, TxAuditContext ctx) {
        StoreToPharmacyTO to = requireTo(toUid);
        if (to.getStatus() != com.otapp.hmis.inventory.domain.SpToStatus.GOODS_ISSUED) {
            throw new InvalidPatientOperationException("Transfer order not goods-issued");
        }
        if (rnRepository.existsByStoreToPharmacyTO(to)) {
            throw new InvalidPatientOperationException("A received note already exists for this transfer order");
        }
        String no = documentNumberService.next(DocumentType.PGRN);
        StoreToPharmacyRN rn = new StoreToPharmacyRN(
                no, to.getPharmacyUid(), to.getStoreUid(), to, ctx.dayUid());
        for (StoreToPharmacyTODetail td : to.getDetails()) {
            StoreToPharmacyRNDetail rd = new StoreToPharmacyRNDetail(
                    rn, td.getItemUid(), td.getMedicineUid(), td.getOrderedPharmacySkuQty(),
                    td.getTransferedPharmacySkuQty(), td.getTransferedStoreSkuQty());
            rn.addDetail(rd);
        }
        rnRepository.save(rn);
        // Re-parent the TO detail's transfer batches onto the matching RN detail (by medicine).
        for (StoreToPharmacyTODetail td : to.getDetails()) {
            StoreToPharmacyRNDetail rd = rn.getDetails().stream()
                    .filter(x -> x.getMedicineUid().equals(td.getMedicineUid())).findFirst().orElseThrow();
            // batches were saved with the TO detail as parent; re-parent each onto the RN detail
            batchRepository.findAll().stream()
                    .filter(b -> b.getStoreToPharmacyTODetail() != null
                            && b.getStoreToPharmacyTODetail().getUid().equals(td.getUid()))
                    .forEach(b -> {
                        b.reparentToRn(rd);
                        rd.attachBatch(b);
                    });
        }
        auditRecorder.record(AUDIT_RN, rn.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toRnDto(rn);
    }

    /**
     * Complete the RN (PENDING→COMPLETED): PHARMACY stock increments here, per re-parented batch via
     * {@code pharmacy::api} (creates destination PharmacyMedicineBatch + IN card). Flips RO + TO to
     * COMPLETED. Guard is in the entity (the double-post fix).
     */
    @Transactional
    public TransferDto completeRn(String rnUid, TxAuditContext ctx) {
        StoreToPharmacyRN rn = rnRepository.findByUid(rnUid)
                .orElseThrow(() -> new NotFoundException("Received note not found: " + rnUid));
        rn.complete();                                    // guards PENDING (service/entity, not controller)
        String reference = "Medicine received # " + rn.getNo();
        for (StoreToPharmacyRNDetail rd : rn.getDetails()) {
            for (StoreToPharmacyBatch b : rd.getBatches()) {
                pharmacyStockCredit.creditTransferLot(
                        rn.getPharmacyUid(), rd.getMedicineUid(), b.getBatchNo(),
                        b.getManufacturedDate(), b.getExpiryDate(), b.getPharmacySkuQty(),
                        reference, ctx);
            }
        }
        StoreToPharmacyTO to = rn.getStoreToPharmacyTO();
        if (to != null) {
            to.markCompleted();
            if (to.getPharmacyToStoreRO() != null) {
                to.getPharmacyToStoreRO().markCompleted();
            }
        }
        auditRecorder.record(AUDIT_RN, rn.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toRnDto(rn);
    }

    // ===================== reads =====================

    @Transactional(readOnly = true)
    public TransferDto getRo(String uid) {
        return InventoryMapper.toRoDto(requireRo(uid));
    }

    @Transactional(readOnly = true)
    public TransferDto getTo(String uid) {
        return InventoryMapper.toToDto(requireTo(uid));
    }

    @Transactional(readOnly = true)
    public TransferDto getRn(String uid) {
        return InventoryMapper.toRnDto(rnRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Received note not found: " + uid)));
    }

    // ===================== helpers =====================

    private PharmacyToStoreRO requireRo(String uid) {
        return roRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Requisition not found: " + uid));
    }

    private StoreToPharmacyTO requireTo(String uid) {
        return toRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Transfer order not found: " + uid));
    }

    private TransferDto auditedRo(PharmacyToStoreRO ro, TxAuditContext ctx) {
        auditRecorder.record(AUDIT_RO, ro.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toRoDto(ro);
    }

    private TransferDto auditedTo(StoreToPharmacyTO to, TxAuditContext ctx) {
        auditRecorder.record(AUDIT_TO, to.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toToDto(to);
    }
}
