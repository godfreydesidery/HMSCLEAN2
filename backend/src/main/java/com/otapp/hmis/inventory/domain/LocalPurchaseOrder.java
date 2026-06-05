package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Local Purchase Order header (inc-08b; legacy LocalPurchaseOrder + LocalPurchaseOrderServiceImpl).
 *
 * <p>State machine (hard guards, verbatim legacy messages): PENDING→VERIFIED (verify, requires
 * details); VERIFIED→APPROVED (approve); APPROVED→SUBMITTED (submit); SUBMITTED→RECEIVED (by GRN
 * approval); REJECTED/RETURNED only from PENDING|VERIFIED (terminal). Edit only while PENDING and
 * only {@code validUntil} is mutable. {@code no} via shared DocumentNumberService (LPO{date}-{seq}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "local_purchase_orders")
public class LocalPurchaseOrder extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private LpoStatus status = LpoStatus.PENDING;

    @Column(name = "status_description", length = 200)
    private String statusDescription;

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @NotBlank
    @Column(name = "supplier_uid", length = 26, nullable = false, updatable = false)
    private String supplierUid;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "valid_until")
    private LocalDate validUntil;            // the only field mutable on edit

    @OneToMany(mappedBy = "localPurchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<LocalPurchaseOrderDetail> details = new ArrayList<>();

    @Column(name = "verified_by_username", length = 80) private String verifiedByUsername;
    @Column(name = "verified_on_day_uid", length = 26)  private String verifiedOnDayUid;
    @Column(name = "verified_at_ts")                    private Instant verifiedAtTs;
    @Column(name = "approved_by_username", length = 80) private String approvedByUsername;
    @Column(name = "approved_on_day_uid", length = 26)  private String approvedOnDayUid;
    @Column(name = "approved_at_ts")                    private Instant approvedAtTs;
    @Column(name = "received_by_username", length = 80) private String receivedByUsername;
    @Column(name = "received_on_day_uid", length = 26)  private String receivedOnDayUid;
    @Column(name = "received_at_ts")                    private Instant receivedAtTs;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public LocalPurchaseOrder(String no, String storeUid, String supplierUid,
                              LocalDate orderDate, LocalDate validUntil, String dayUid) {
        this.no = no;
        this.status = LpoStatus.PENDING;
        this.statusDescription = "Order pending for verification";
        this.storeUid = storeUid;
        this.supplierUid = supplierUid;
        this.orderDate = orderDate;
        this.validUntil = validUntil;
        this.businessDayUid = dayUid;
    }

    public void addDetail(LocalPurchaseOrderDetail detail) {
        this.details.add(detail);
    }

    /** Edit: only while PENDING, and only {@code validUntil} (legacy :88-103). */
    public void editValidUntil(LocalDate newValidUntil) {
        if (this.status != LpoStatus.PENDING) {
            throw new InvalidPatientOperationException("Can not edit. Only pending orders can be edited");
        }
        this.validUntil = newValidUntil;
    }

    public void verify(String actor, String dayUid, Instant now) {
        if (this.status != LpoStatus.PENDING) {
            throw new InvalidPatientOperationException("Could not verify. Only pending orders can be verified");
        }
        requireDetails("Could not verify. Order has no items");
        this.status = LpoStatus.VERIFIED;
        this.statusDescription = "Order awaiting for approval";
        this.verifiedByUsername = actor;
        this.verifiedOnDayUid = dayUid;
        this.verifiedAtTs = now;
    }

    public void approve(String actor, String dayUid, Instant now) {
        if (this.status != LpoStatus.VERIFIED) {
            throw new InvalidPatientOperationException("Could not approve. Only verified orders can be approved");
        }
        requireDetails("Could not approve. Order has no items");
        this.status = LpoStatus.APPROVED;
        this.statusDescription = "Order awaiting for submission";
        this.approvedByUsername = actor;
        this.approvedOnDayUid = dayUid;
        this.approvedAtTs = now;
    }

    public void submit() {
        if (this.status != LpoStatus.APPROVED) {
            throw new InvalidPatientOperationException("Could not submit. Only approved orders can be submitted");
        }
        requireDetails("Could not submit. Order has no items");
        this.status = LpoStatus.SUBMITTED;
        this.statusDescription = "Submited to supplier. Order awaiting for delivery";
    }

    /** SUBMITTED → RECEIVED, flipped by GRN approval (legacy GoodsReceivedNoteServiceImpl.java:184-190). */
    public void markReceived(String actor, String dayUid, Instant now) {
        this.status = LpoStatus.RECEIVED;
        this.statusDescription = "Order received";
        this.receivedByUsername = actor;
        this.receivedOnDayUid = dayUid;
        this.receivedAtTs = now;
    }

    public void returnForAmendment() {
        if (this.status != LpoStatus.PENDING && this.status != LpoStatus.VERIFIED) {
            throw new InvalidPatientOperationException(
                    "Could not returned. Only PENDING or VERIFIED orders can be returned");
        }
        this.status = LpoStatus.RETURNED;
        this.statusDescription = "Order returned for ammendment";
    }

    public void reject() {
        if (this.status != LpoStatus.PENDING && this.status != LpoStatus.VERIFIED) {
            throw new InvalidPatientOperationException(
                    "Could not reject. Only PENDING or VERIFIED orders can be rejected");
        }
        this.status = LpoStatus.REJECTED;
        this.statusDescription = "Order rejected";
    }

    private void requireDetails(String message) {
        if (this.details.isEmpty()) {
            throw new InvalidPatientOperationException(message);
        }
    }
}
