package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.application.dto.GrnBatchRequest;
import com.otapp.hmis.inventory.application.dto.GrnDto;
import com.otapp.hmis.inventory.domain.GoodsReceivedNote;
import com.otapp.hmis.inventory.domain.GoodsReceivedNoteDetail;
import com.otapp.hmis.inventory.domain.GoodsReceivedNoteDetailBatch;
import com.otapp.hmis.inventory.domain.GoodsReceivedNoteDetailBatchRepository;
import com.otapp.hmis.inventory.domain.GoodsReceivedNoteDetailRepository;
import com.otapp.hmis.inventory.domain.GoodsReceivedNoteRepository;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrder;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrderRepository;
import com.otapp.hmis.inventory.domain.LpoStatus;
import com.otapp.hmis.inventory.domain.Purchase;
import com.otapp.hmis.inventory.domain.PurchaseRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.documentnumber.DocumentNumberService;
import com.otapp.hmis.shared.documentnumber.DocumentType;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods Received Note service (inc-08b; legacy GoodsReceivedNoteServiceImpl).
 *
 * <p>Create requires a SUBMITTED LPO whose store matches + one-GRN-per-LPO; seeds a GRN detail per
 * LPO line (orderedQty=lpo.qty, receivedQty=0, price=lpo.price, NOT-VERIFIED). Per-line receivedQty
 * entry + verify (sum(batch.qty)==receivedQty). {@code approve()} is ONE transaction: guard PENDING +
 * every detail VERIFIED, then per detail with receivedQty>0 → credit store stock + RECEIPT card +
 * copy batches to new StoreItemBatch + write a Purchase row; then LPO→RECEIVED, GRN→APPROVED.
 * NO three-way match (Q3). GRN no via shared DocumentNumberService.
 */
@Service
@RequiredArgsConstructor
public class GrnService {

    private final GoodsReceivedNoteRepository grnRepository;
    private final GoodsReceivedNoteDetailRepository detailRepository;
    private final GoodsReceivedNoteDetailBatchRepository batchRepository;
    private final LocalPurchaseOrderRepository lpoRepository;
    private final PurchaseRepository purchaseRepository;
    private final StoreStockService storeStockService;
    private final DocumentNumberService documentNumberService;
    private final AuditRecorder auditRecorder;

    private static final String AUDIT = "inventory.GoodsReceivedNote";

    /**
     * Create a GRN from a SUBMITTED LPO (store-match + one-per-LPO guards;
     * GoodsReceivedNoteResource.java:97-117, GoodsReceivedNoteServiceImpl.java:64-108).
     */
    @Transactional
    public GrnDto createFromLpo(String lpoUid, String requestStoreUid, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = lpoRepository.findByUid(lpoUid)
                .orElseThrow(() -> new NotFoundException("Local purchase order not found: " + lpoUid));
        if (!lpo.getStoreUid().equals(requestStoreUid)) {
            throw new InvalidPatientOperationException("Order not designated to the selected store");
        }
        if (lpo.getStatus() != LpoStatus.SUBMITTED) {
            throw new InvalidPatientOperationException(
                    "Could not create GRN. Local Purchase Order not submitted");
        }
        if (grnRepository.existsByLocalPurchaseOrder(lpo)) {
            throw new InvalidPatientOperationException("A GRN already exists for this order");
        }
        String no = documentNumberService.next(DocumentType.GRN);
        GoodsReceivedNote grn = new GoodsReceivedNote(no, lpo.getStoreUid(), lpo, ctx.dayUid());
        lpo.getDetails().forEach(d -> grn.addDetail(new GoodsReceivedNoteDetail(
                grn, d.getItemUid(), d.getQty(), d.getPrice())));
        GoodsReceivedNote saved = grnRepository.save(grn);
        auditRecorder.record(AUDIT, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toGrnDto(saved);
    }

    @Transactional
    public GrnDto setReceivedQty(String detailUid, BigDecimal received, TxAuditContext ctx) {
        GoodsReceivedNoteDetail detail = requireDetail(detailUid);
        detail.setReceivedQty(received);
        auditRecorder.record(AUDIT, detail.getGoodsReceivedNote().getUid(), AuditAction.UPDATE,
                ctx.actorUsername());
        return InventoryMapper.toGrnDto(detail.getGoodsReceivedNote());
    }

    @Transactional
    public GrnDto addBatch(String detailUid, GrnBatchRequest req, TxAuditContext ctx) {
        GoodsReceivedNoteDetail detail = requireDetail(detailUid);
        GoodsReceivedNoteDetailBatch batch = new GoodsReceivedNoteDetailBatch(
                detail, req.batchNo(), req.manufacturedDate(), req.expiryDate(), req.qty());
        detail.addBatch(batch);
        batchRepository.save(batch);
        return InventoryMapper.toGrnDto(detail.getGoodsReceivedNote());
    }

    @Transactional
    public GrnDto verifyDetail(String detailUid, TxAuditContext ctx) {
        GoodsReceivedNoteDetail detail = requireDetail(detailUid);
        detail.verify();
        auditRecorder.record(AUDIT, detail.getGoodsReceivedNote().getUid(), AuditAction.UPDATE,
                ctx.actorUsername());
        return InventoryMapper.toGrnDto(detail.getGoodsReceivedNote());
    }

    /**
     * Approve the GRN: ONE transaction doing all stock effects (legacy :110-190). Guard PENDING +
     * every detail VERIFIED (on the aggregate), then per detail with receivedQty>0 credit store
     * stock, copy each GRN batch into a new StoreItemBatch, write a Purchase row; then LPO→RECEIVED.
     */
    @Transactional
    public GrnDto approve(String grnUid, TxAuditContext ctx) {
        GoodsReceivedNote grn = require(grnUid);
        grn.approve(ctx.actorUsername(), ctx.dayUid(), instant(ctx));  // guards PENDING + all VERIFIED

        Instant now = instant(ctx);
        for (GoodsReceivedNoteDetail detail : grn.getDetails()) {
            BigDecimal received = detail.getReceivedQty();
            if (received.compareTo(BigDecimal.ZERO) > 0) {
                String reference = "Goods received GRN# " + grn.getNo();
                // credit aggregate + RECEIPT card, then copy each GRN batch to a NEW StoreItemBatch
                detail.getBatches().forEach(b -> storeStockService.receiveBatch(
                        grn.getStoreUid(), detail.getItemUid(), b.getBatchNo(),
                        b.getManufacturedDate(), b.getExpiryDate(), b.getQty(), reference, ctx));
                // Purchase ledger row (qty=received, amount=received*price) — only when LPO-linked
                if (grn.getLocalPurchaseOrder() != null) {
                    BigDecimal amount = received.multiply(detail.getPrice());
                    purchaseRepository.save(new Purchase(
                            grn, detail.getItemUid(), received, amount, ctx.dayUid()));
                }
            }
        }
        if (grn.getLocalPurchaseOrder() != null) {
            grn.getLocalPurchaseOrder().markReceived(ctx.actorUsername(), ctx.dayUid(), now);
        }
        auditRecorder.record(AUDIT, grn.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toGrnDto(grn);
    }

    @Transactional(readOnly = true)
    public GrnDto getByUid(String grnUid) {
        return InventoryMapper.toGrnDto(require(grnUid));
    }

    private GoodsReceivedNote require(String uid) {
        return grnRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Goods received note not found: " + uid));
    }

    private GoodsReceivedNoteDetail requireDetail(String uid) {
        return detailRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("GRN detail not found: " + uid));
    }

    private static Instant instant(TxAuditContext ctx) {
        return ctx.timestamp() != null ? ctx.timestamp() : Instant.now();
    }
}
