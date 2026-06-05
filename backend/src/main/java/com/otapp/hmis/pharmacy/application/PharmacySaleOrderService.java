package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.masterdata.lookup.MedicineLookup;
import com.otapp.hmis.masterdata.lookup.MedicinePriceLookup;
import com.otapp.hmis.masterdata.lookup.PharmacyLookup;
import com.otapp.hmis.pharmacy.domain.MovementType;
import com.otapp.hmis.pharmacy.domain.OtcOrderStatus;
import com.otapp.hmis.pharmacy.domain.PharmacyCustomer;
import com.otapp.hmis.pharmacy.domain.PharmacyCustomerRepository;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrder;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderDetail;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderDetailRepository;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderRepository;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDetailDto;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDetailRequest;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDto;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderRequest;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OTC walk-in {@code PharmacySaleOrder} lifecycle service (inc-08a chunk 4). Reproduces the legacy
 * flow (PatientServiceImpl.java:3019-3442; PatientResource.java:6212-6406) verbatim.
 *
 * <p>State machine: create→PENDING; add-detail (PENDING-only) creates a flat-CASH bill against the
 * GENERAL patient sentinel (Q9 — Medicine.price×qty, NOT the plan-pricing engine); PENDING→APPROVED
 * is the bill-payment side effect (handled by {@code OtcSettlementListener}); whole-order dispense
 * (APPROVED-only) decrements aggregate stock with NO FEFO/batch (Q9/CR-08-FEFO-ON-OTC); cancel
 * (PENDING-only); archive (APPROVED + all GIVEN); 24h auto-sweeps.
 *
 * <p>The GENERAL dummy patient: legacy used a {@code patients} row with {@code no='GENERAL'} as the
 * billing anchor. {@code PatientBill.patientUid} is a loose VARCHAR(26) with NO FK (ADR-0008 §1), so
 * we use the fixed sentinel {@code "GENERAL"} — faithful to the intent (OTC bills are not tied to a
 * real patient) without needing a cross-module registration row. Deliberate, documented modeling.
 */
@Service
@RequiredArgsConstructor
public class PharmacySaleOrderService {

    /** Loose sentinel patientUid for OTC bills (legacy GENERAL dummy patient; no FK in the new model). */
    static final String GENERAL_PATIENT_UID = "GENERAL";

    private final PharmacySaleOrderRepository orderRepository;
    private final PharmacySaleOrderDetailRepository detailRepository;
    private final PharmacyCustomerRepository customerRepository;
    private final PharmacyLookup pharmacyLookup;
    private final MedicineLookup medicineLookup;
    private final MedicinePriceLookup medicinePriceLookup;
    private final BillingCommands billingCommands;
    private final StockService stockService;
    private final AuditRecorder auditRecorder;

    private static final String AUDIT_ORDER = "pharmacy.PharmacySaleOrder";
    private static final String AUDIT_DETAIL = "pharmacy.PharmacySaleOrderDetail";

    // =========================================================================
    // Create order (PENDING)
    // =========================================================================

    @Transactional
    public SaleOrderDto createOrder(SaleOrderRequest req, TxAuditContext ctx) {
        if (!pharmacyLookup.existsByUid(req.pharmacyUid())) {        // Q2: server-validate, no affiliation
            throw new NotFoundException("Pharmacy not found: " + req.pharmacyUid());
        }
        // Resolve-or-create the walk-in customer (PCST/{seq} numbering, CR-09-NUM1).
        PharmacyCustomer customer;
        if (req.customerUid() != null && !req.customerUid().isBlank()) {
            customer = customerRepository.findByUid(req.customerUid())
                    .orElseThrow(() -> new NotFoundException("Invalid customer"));
        } else {
            String custNo = "PCST/" + customerRepository.nextPcstNo();
            customer = customerRepository.save(new PharmacyCustomer(
                    custNo, req.customerName(), req.customerGender(),
                    req.customerPhoneNo(), req.customerAddress()));
        }
        String orderNo = "PSO/" + orderRepository.nextPsoNo();
        PharmacySaleOrder order = new PharmacySaleOrder(orderNo, req.pharmacyUid(),
                req.pharmacistUid(), customer, req.comments(),
                ctx.actorUsername(), ctx.dayUid(), instant(ctx));
        PharmacySaleOrder saved = orderRepository.save(order);
        auditRecorder.record(AUDIT_ORDER, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return SaleOrderMapper.toDto(saved);
    }

    // =========================================================================
    // Add detail (PENDING-only) — flat-CASH bill against GENERAL patient
    // =========================================================================

    @Transactional
    public SaleOrderDetailDto addDetail(String orderUid, SaleOrderDetailRequest req,
                                        TxAuditContext ctx) {
        PharmacySaleOrder order = requireOrder(orderUid);
        if (order.getStatus() != OtcOrderStatus.PENDING) {
            throw new InvalidPatientOperationException("Only pending orders can be updated");
        }
        if (!medicineLookup.existsByUid(req.medicineUid())) {
            throw new NotFoundException("Medicine not found");
        }
        PharmacySaleOrderDetail detail = new PharmacySaleOrderDetail(
                order, req.medicineUid(), req.qty(),
                req.dosage(), req.frequency(), req.route(), req.days(),
                ctx.actorUsername(), ctx.dayUid(), instant(ctx));
        order.addDetail(detail);

        // Flat-CASH bill: Medicine.price * qty (Q9 — NOT recordClinicalCharge). Against GENERAL.
        BigDecimal unitPrice = medicinePriceLookup.priceOf(req.medicineUid());
        String billUid = billingCommands.recordFlatCashSale(
                GENERAL_PATIENT_UID, ServiceKind.MEDICINE, "Medicine Sale",
                "Medicine: " + req.medicineUid(), req.medicineUid(),
                req.qty(), unitPrice, ctx);
        detail.linkBill(billUid);

        PharmacySaleOrderDetail saved = detailRepository.save(detail);
        auditRecorder.record(AUDIT_DETAIL, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return SaleOrderMapper.toDetailDto(saved);
    }

    // =========================================================================
    // Whole-order dispense (APPROVED-only) — aggregate decrement, NO FEFO
    // =========================================================================

    @Transactional
    public SaleOrderDto dispense(String orderUid, String pharmacyUid, TxAuditContext ctx) {
        if (!pharmacyLookup.existsByUid(pharmacyUid)) {
            throw new NotFoundException("Pharmacy not found: " + pharmacyUid);
        }
        PharmacySaleOrder order = requireOrder(orderUid);
        if (order.getStatus() != OtcOrderStatus.APPROVED) {
            throw new InvalidPatientOperationException("Order not approved");
        }
        for (PharmacySaleOrderDetail detail : order.getDetails()) {
            // dispense() enforces NOT-GIVEN guard + all-or-nothing (issued==qty).
            detail.dispense(detail.getQty(), pharmacyUid,
                    ctx.actorUsername(), ctx.dayUid(), instant(ctx));
            // aggregate decrement + stock-card OUT (NO batch/FEFO — Q9). Reference verbatim legacy.
            stockService.decrementAggregateOnly(pharmacyUid, detail.getMedicineUid(),
                    detail.getQty(), MovementType.DISPENSE,
                    "Issued in sale: id " + detail.getUid(), ctx);
        }
        auditRecorder.record(AUDIT_ORDER, order.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return SaleOrderMapper.toDto(order);
    }

    // =========================================================================
    // Cancel / archive
    // =========================================================================

    @Transactional
    public SaleOrderDto cancel(String orderUid, TxAuditContext ctx) {
        PharmacySaleOrder order = requireOrder(orderUid);
        order.cancel(ctx.actorUsername(), ctx.dayUid(), instant(ctx), null);
        auditRecorder.record(AUDIT_ORDER, order.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return SaleOrderMapper.toDto(order);
    }

    @Transactional
    public SaleOrderDto archive(String orderUid, TxAuditContext ctx) {
        PharmacySaleOrder order = requireOrder(orderUid);
        order.archive();
        auditRecorder.record(AUDIT_ORDER, order.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return SaleOrderMapper.toDto(order);
    }

    // =========================================================================
    // Delete detail (PENDING/NOT-GIVEN + bill UNPAID)
    // =========================================================================

    @Transactional
    public void deleteDetail(String detailUid, TxAuditContext ctx) {
        PharmacySaleOrderDetail detail = detailRepository.findByUid(detailUid)
                .orElseThrow(() -> new NotFoundException("Sale order detail not found: " + detailUid));
        if (!detail.isDeletable()) {
            throw new InvalidPatientOperationException("Medicine already given");
        }
        if (detail.getPatientBillUid() != null) {
            billingCommands.cancelCharge(detail.getPatientBillUid(), "Deleted sale detail", ctx);
        }
        PharmacySaleOrder order = detail.getPharmacySaleOrder();
        order.getDetails().remove(detail);          // orphanRemoval deletes the row
        detailRepository.delete(detail);
        auditRecorder.record(AUDIT_DETAIL, detailUid, AuditAction.DELETE, ctx.actorUsername());
    }

    // =========================================================================
    // 24h auto-sweeps (explicit `now` — test-injectable)
    // =========================================================================

    @Transactional
    public int cancelStaleOrders(Instant now, TxAuditContext ctx) {
        int swept = 0;
        for (PharmacySaleOrder order : orderRepository.findByStatus(OtcOrderStatus.PENDING)) {
            if (order.getCreatedAtTs() != null
                    && ChronoUnit.HOURS.between(order.getCreatedAtTs(), now) >= 24) {
                order.cancel(ctx.actorUsername(), ctx.dayUid(), now, "Autocanceled after expiry");
                auditRecorder.record(AUDIT_ORDER, order.getUid(), AuditAction.UPDATE,
                        ctx.actorUsername());
                swept++;
            }
        }
        return swept;
    }

    @Transactional
    public int archiveStaleOrders(Instant now, TxAuditContext ctx) {
        int swept = 0;
        for (PharmacySaleOrder order : orderRepository.findByStatus(OtcOrderStatus.APPROVED)) {
            boolean allGiven = !order.getDetails().isEmpty()
                    && order.getDetails().stream()
                        .allMatch(d -> d.getStatus()
                                == com.otapp.hmis.pharmacy.domain.OtcFulfilmentStatus.GIVEN);
            if (allGiven && order.getApprovedAtTs() != null
                    && ChronoUnit.HOURS.between(order.getApprovedAtTs(), now) >= 24) {
                order.archive();
                auditRecorder.record(AUDIT_ORDER, order.getUid(), AuditAction.UPDATE,
                        ctx.actorUsername());
                swept++;
            }
        }
        return swept;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @Transactional(readOnly = true)
    public SaleOrderDto getByUid(String orderUid) {
        return SaleOrderMapper.toDto(requireOrder(orderUid));
    }

    /** Worklist FILTER: orders in PENDING or APPROVED (legacy PatientServiceImpl.java:3019-3026). */
    @Transactional(readOnly = true)
    public List<SaleOrderDto> worklist() {
        return orderRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of(OtcOrderStatus.PENDING, OtcOrderStatus.APPROVED)).stream()
                .map(SaleOrderMapper::toDto)
                .toList();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PharmacySaleOrder requireOrder(String uid) {
        return orderRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Sale order not found: " + uid));
    }

    private static Instant instant(TxAuditContext ctx) {
        return ctx.timestamp() != null ? ctx.timestamp() : Instant.now();
    }
}
