package com.otapp.hmis.pharmacy.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OTC walk-in sale-order header (inc-08a chunk 4; legacy PharmacySaleOrder.java:36-100).
 *
 * <p>State machine (OtcOrderStatus, PatientServiceImpl.java:3019-3026,3341,3079,3138):
 * PENDING (create) → APPROVED (side effect of paying the linked bills, via the BillSettledEvent
 * seam) → ARCHIVED (after all details GIVEN); plus PENDING → CANCELED. paymentType is hardcoded
 * 'CASH' (Q9 — incoming value ignored). {@code no} = 'PSO/'+nextval(seq_pso_no) (CR-09-NUM1).
 *
 * <p>Cross-module refs ({@code pharmacyUid}, {@code pharmacistUid}) are loose uids (ADR-0008 §1);
 * the customer is an intra-module {@code @ManyToOne}. Details are an intra-module {@code @OneToMany}
 * (orphanRemoval — legacy detail delete removes the child).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_sale_orders")
public class PharmacySaleOrder extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    /** Hardcoded 'CASH' (Q9 — incoming paymentType ignored). */
    @NotBlank
    @Column(name = "payment_type", length = 40, nullable = false)
    private String paymentType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OtcOrderStatus status = OtcOrderStatus.PENDING;

    @Column(name = "comments", length = 1000)
    private String comments;

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @NotBlank
    @Column(name = "pharmacist_uid", length = 26, nullable = false)
    private String pharmacistUid;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_customer_id", nullable = false, updatable = false)
    private PharmacyCustomer pharmacyCustomer;

    @OneToMany(mappedBy = "pharmacySaleOrder", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<PharmacySaleOrderDetail> details = new ArrayList<>();

    // Created / approved / canceled audit triplets (legacy business-event audit).
    @Column(name = "created_by_username", length = 80) private String createdByUsername;
    @Column(name = "created_on_day_uid", length = 26)  private String createdOnDayUid;
    @Column(name = "created_at_ts")                     private Instant createdAtTs;
    @Column(name = "approved_by_username", length = 80) private String approvedByUsername;
    @Column(name = "approved_on_day_uid", length = 26)  private String approvedOnDayUid;
    @Column(name = "approved_at_ts")                    private Instant approvedAtTs;
    @Column(name = "canceled_by_username", length = 80) private String canceledByUsername;
    @Column(name = "canceled_on_day_uid", length = 26)  private String canceledOnDayUid;
    @Column(name = "canceled_at_ts")                    private Instant canceledAtTs;

    /**
     * Create a PENDING OTC order. paymentType is forced to 'CASH' (Q9).
     */
    public PharmacySaleOrder(String no, String pharmacyUid, String pharmacistUid,
                             PharmacyCustomer customer, String comments,
                             String actorUsername, String dayUid, Instant now) {
        this.no = no;
        this.paymentType = "CASH";                      // hardcoded — Q9
        this.status = OtcOrderStatus.PENDING;
        this.pharmacyUid = pharmacyUid;
        this.pharmacistUid = pharmacistUid;
        this.pharmacyCustomer = customer;
        this.comments = comments;
        this.createdByUsername = actorUsername;
        this.createdOnDayUid = dayUid;
        this.createdAtTs = now;
    }

    public void addDetail(PharmacySaleOrderDetail detail) {
        this.details.add(detail);
    }

    /** PENDING → APPROVED (bill-payment side effect). Stamps the approved audit. */
    public void approve(String actorUsername, String dayUid, Instant now) {
        // Idempotent on an already-APPROVED order (the legacy listener flips on first matching detail).
        if (this.status == OtcOrderStatus.APPROVED) {
            return;
        }
        if (this.status != OtcOrderStatus.PENDING) {
            throw new InvalidPatientOperationException("Only pending orders can be approved");
        }
        this.status = OtcOrderStatus.APPROVED;
        this.approvedByUsername = actorUsername;
        this.approvedOnDayUid = dayUid;
        this.approvedAtTs = now;
    }

    /** PENDING → CANCELED (manual or 24h auto-expiry). */
    public void cancel(String actorUsername, String dayUid, Instant now, String comment) {
        if (this.status != OtcOrderStatus.PENDING) {
            throw new InvalidPatientOperationException("Only pending orders can be canceled");
        }
        this.status = OtcOrderStatus.CANCELED;
        this.canceledByUsername = actorUsername;
        this.canceledOnDayUid = dayUid;
        this.canceledAtTs = now;
        if (comment != null) {
            this.comments = comment;
        }
    }

    /** APPROVED + all details GIVEN → ARCHIVED. */
    public void archive() {
        if (this.status != OtcOrderStatus.APPROVED
                || !details.stream().allMatch(d -> d.getStatus() == OtcFulfilmentStatus.GIVEN)) {
            throw new InvalidPatientOperationException("Only given orders can be archived");
        }
        this.status = OtcOrderStatus.ARCHIVED;
    }
}
