package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.application.dto.LpoDetailRequest;
import com.otapp.hmis.inventory.application.dto.LpoDto;
import com.otapp.hmis.inventory.application.dto.LpoRequest;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrder;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrderDetail;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrderRepository;
import com.otapp.hmis.inventory.domain.LpoStatus;
import com.otapp.hmis.masterdata.lookup.ItemLookup;
import com.otapp.hmis.masterdata.lookup.StoreLookup;
import com.otapp.hmis.masterdata.lookup.SupplierItemPriceLookup;
import com.otapp.hmis.masterdata.lookup.SupplierLookup;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.documentnumber.DocumentNumberService;
import com.otapp.hmis.shared.documentnumber.DocumentType;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local Purchase Order lifecycle service (inc-08b; legacy LocalPurchaseOrderServiceImpl).
 *
 * <p>Server-assigns the LPO {@code no} via the shared DocumentNumberService (LPO{date}-{seq}) — the
 * concurrency-safe replacement for the legacy MAX(id)+1 (ADR-0009 §5). State transitions are hard
 * guards on the aggregate (verbatim legacy messages). Detail price is COPIED from the
 * SupplierItemPrice; a (supplier,item) with no price row is hard-rejected.
 */
@Service
@RequiredArgsConstructor
public class LpoService {

    private final LocalPurchaseOrderRepository lpoRepository;
    private final StoreLookup storeLookup;
    private final SupplierLookup supplierLookup;
    private final ItemLookup itemLookup;
    private final SupplierItemPriceLookup supplierItemPriceLookup;
    private final DocumentNumberService documentNumberService;
    private final AuditRecorder auditRecorder;

    private static final String AUDIT = "inventory.LocalPurchaseOrder";

    @Transactional
    public LpoDto create(LpoRequest req, TxAuditContext ctx) {
        if (!storeLookup.existsByUid(req.storeUid())) {
            throw new NotFoundException("Store not found: " + req.storeUid());
        }
        if (!supplierLookup.existsByUid(req.supplierUid())) {
            throw new NotFoundException("Supplier not found: " + req.supplierUid());
        }
        String no = documentNumberService.next(DocumentType.LPO);
        LocalPurchaseOrder lpo = new LocalPurchaseOrder(
                no, req.storeUid(), req.supplierUid(), req.orderDate(), req.validUntil(), ctx.dayUid());
        LocalPurchaseOrder saved = lpoRepository.save(lpo);
        auditRecorder.record(AUDIT, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return InventoryMapper.toLpoDto(saved);
    }

    @Transactional
    public LpoDto editValidUntil(String lpoUid, LocalDate validUntil, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        lpo.editValidUntil(validUntil);
        auditRecorder.record(AUDIT, lpo.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toLpoDto(lpo);
    }

    @Transactional
    public LpoDto addDetail(String lpoUid, LpoDetailRequest req, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        if (lpo.getStatus() != LpoStatus.PENDING) {
            throw new InvalidPatientOperationException("Could not edit. only PENDING LPO can be edited");
        }
        if (!itemLookup.existsByUid(req.itemUid())) {
            throw new NotFoundException("Item not found");
        }
        // Price source = SupplierItemPrice; absent (supplier,item) pair is hard-rejected (legacy).
        BigDecimal price = supplierItemPriceLookup.priceFor(lpo.getSupplierUid(), req.itemUid())
                .orElseThrow(() -> new InvalidPatientOperationException(
                        "Item not valid for this supplier"));
        // Duplicate-item guard.
        boolean dup = lpo.getDetails().stream().anyMatch(d -> d.getItemUid().equals(req.itemUid()));
        if (dup) {
            throw new InvalidPatientOperationException("Duplicates items are not allowed");
        }
        lpo.addDetail(new LocalPurchaseOrderDetail(lpo, req.itemUid(), req.qty(), price));
        auditRecorder.record(AUDIT, lpo.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toLpoDto(lpo);
    }

    @Transactional
    public LpoDto verify(String lpoUid, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        lpo.verify(ctx.actorUsername(), ctx.dayUid(), instant(ctx));
        return audited(lpo, ctx);
    }

    @Transactional
    public LpoDto approve(String lpoUid, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        lpo.approve(ctx.actorUsername(), ctx.dayUid(), instant(ctx));
        return audited(lpo, ctx);
    }

    @Transactional
    public LpoDto submit(String lpoUid, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        lpo.submit();
        return audited(lpo, ctx);
    }

    @Transactional
    public LpoDto reject(String lpoUid, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        lpo.reject();
        return audited(lpo, ctx);
    }

    @Transactional
    public LpoDto returnForAmendment(String lpoUid, TxAuditContext ctx) {
        LocalPurchaseOrder lpo = require(lpoUid);
        lpo.returnForAmendment();
        return audited(lpo, ctx);
    }

    @Transactional(readOnly = true)
    public LpoDto getByUid(String lpoUid) {
        return InventoryMapper.toLpoDto(require(lpoUid));
    }

    private LpoDto audited(LocalPurchaseOrder lpo, TxAuditContext ctx) {
        auditRecorder.record(AUDIT, lpo.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return InventoryMapper.toLpoDto(lpo);
    }

    private LocalPurchaseOrder require(String uid) {
        return lpoRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Local purchase order not found: " + uid));
    }

    private static Instant instant(TxAuditContext ctx) {
        return ctx.timestamp() != null ? ctx.timestamp() : Instant.now();
    }
}
