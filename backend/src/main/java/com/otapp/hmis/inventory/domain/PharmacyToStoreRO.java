package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Pharmacy→Store requisition (PSR) header (inc-08b chunk 6; legacy PharmacyToStoreRO). Moves NO
 * stock — it is request-only. State machine + verbatim guard messages per
 * PharmacyToStoreROServiceImpl.java:81-279.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_store_ros")
public class PharmacyToStoreRO extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    @NotNull
    @Convert(converter = PsRoStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private PsRoStatus status = PsRoStatus.PENDING;

    @Column(name = "status_description", length = 200)
    private String statusDescription;

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @OneToMany(mappedBy = "pharmacyToStoreRO", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<PharmacyToStoreRODetail> details = new ArrayList<>();

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public PharmacyToStoreRO(String no, String pharmacyUid, String storeUid, String dayUid) {
        this.no = no;
        this.status = PsRoStatus.PENDING;
        this.statusDescription = "Order pending for verification";
        this.pharmacyUid = pharmacyUid;
        this.storeUid = storeUid;
        this.businessDayUid = dayUid;
    }

    public void addDetail(PharmacyToStoreRODetail detail) {
        this.details.add(detail);
    }

    public void verify() {
        requireStatus(PsRoStatus.PENDING, "Could not verify. Only pending orders can be verified");
        requireDetails();
        this.status = PsRoStatus.VERIFIED;
        this.statusDescription = "Order awaiting for approval";
    }

    public void approve() {
        requireStatus(PsRoStatus.VERIFIED, "Could not approve. Only verified orders can be approved");
        this.status = PsRoStatus.APPROVED;
        this.statusDescription = "Order awaiting for submission";
    }

    public void submit() {
        requireStatus(PsRoStatus.APPROVED, "Could not submit. Only approved orders can be submitted");
        this.status = PsRoStatus.SUBMITTED;
        this.statusDescription = "Submited to store. Order awaiting for processing";
    }

    /** SUBMITTED → IN-PROCESS, set when the store creates the TO. */
    public void markInProcess() {
        requireStatus(PsRoStatus.SUBMITTED, "Order not submitted");
        this.status = PsRoStatus.IN_PROCESS;
        this.statusDescription = "Order in process at store";
    }

    /** IN-PROCESS → GOODS-ISSUED, set when the store issues the TO. */
    public void markGoodsIssued() {
        requireStatus(PsRoStatus.IN_PROCESS, "Order not in process");
        this.status = PsRoStatus.GOODS_ISSUED;
        this.statusDescription = "Goods issued by store";
    }

    /** GOODS-ISSUED → COMPLETED, set when the pharmacy completes the RN. */
    public void markCompleted() {
        this.status = PsRoStatus.COMPLETED;
        this.statusDescription = "Order completed";
    }

    public void returnForAmendment() {
        requireStatus(PsRoStatus.SUBMITTED,
                "Could not returned. Only SUBMITTED orders can be returned");
        this.status = PsRoStatus.RETURNED;
        this.statusDescription = "Order returned for ammendment";
    }

    public void reject() {
        requireStatus(PsRoStatus.SUBMITTED, "Could not reject. Only SUBMITTED orders can be rejected");
        this.status = PsRoStatus.REJECTED;
        this.statusDescription = "Order rejected";
    }

    private void requireStatus(PsRoStatus expected, String message) {
        if (this.status != expected) {
            throw new InvalidPatientOperationException(message);
        }
    }

    private void requireDetails() {
        if (this.details.isEmpty()) {
            throw new InvalidPatientOperationException("Order has no items");
        }
    }
}
