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
 * Pharmacy→Pharmacy requisition (PPR) header (inc-08b chunk 7; legacy PharmacyToPharmacyRO). Moves
 * NO stock. Reuses the 9-state {@link PsRoStatus} (identical value set). Requesting + delivering
 * pharmacies must differ (legacy "Order can not be placed in the same pharmacy").
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_ros")
public class PharmacyToPharmacyRO extends AuditableEntity {

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
    @Column(name = "requesting_pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String requestingPharmacyUid;

    @NotBlank
    @Column(name = "delivering_pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String deliveringPharmacyUid;

    @OneToMany(mappedBy = "pharmacyToPharmacyRO", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<PharmacyToPharmacyRODetail> details = new ArrayList<>();

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public PharmacyToPharmacyRO(String no, String requestingPharmacyUid, String deliveringPharmacyUid,
                                String dayUid) {
        if (requestingPharmacyUid.equals(deliveringPharmacyUid)) {
            throw new InvalidPatientOperationException("Order can not be placed in the same pharmacy");
        }
        this.no = no;
        this.status = PsRoStatus.PENDING;
        this.statusDescription = "Order pending for verification";
        this.requestingPharmacyUid = requestingPharmacyUid;
        this.deliveringPharmacyUid = deliveringPharmacyUid;
        this.businessDayUid = dayUid;
    }

    public void addDetail(PharmacyToPharmacyRODetail detail) {
        this.details.add(detail);
    }

    public void verify() {
        require(PsRoStatus.PENDING, "Could not verify. Only pending orders can be verified");
        if (details.isEmpty()) {
            throw new InvalidPatientOperationException("Order has no items");
        }
        this.status = PsRoStatus.VERIFIED;
        this.statusDescription = "Order awaiting for approval";
    }

    public void approve() {
        require(PsRoStatus.VERIFIED, "Could not approve. Only verified orders can be approved");
        this.status = PsRoStatus.APPROVED;
        this.statusDescription = "Order awaiting for submission";
    }

    public void submit() {
        require(PsRoStatus.APPROVED, "Could not submit. Only approved orders can be submitted");
        this.status = PsRoStatus.SUBMITTED;
        this.statusDescription = "Submited. Order awaiting for processing";
    }

    public void markInProcess() {
        require(PsRoStatus.SUBMITTED, "Can only create transfer order for submitted requests");
        this.status = PsRoStatus.IN_PROCESS;
        this.statusDescription = "Order in process";
    }

    public void markGoodsIssued() {
        require(PsRoStatus.IN_PROCESS, "Order not in process");
        this.status = PsRoStatus.GOODS_ISSUED;
        this.statusDescription = "Goods issued";
    }

    public void markCompleted() {
        this.status = PsRoStatus.COMPLETED;
        this.statusDescription = "Order completed";
    }

    public void returnForAmendment() {
        require(PsRoStatus.SUBMITTED, "Could not returned. Only SUBMITTED orders can be returned");
        this.status = PsRoStatus.RETURNED;
        this.statusDescription = "Order returned for ammendment";
    }

    public void reject() {
        require(PsRoStatus.SUBMITTED, "Could not reject. Only SUBMITTED orders can be rejected");
        this.status = PsRoStatus.REJECTED;
        this.statusDescription = "Order rejected";
    }

    private void require(PsRoStatus expected, String message) {
        if (this.status != expected) {
            throw new InvalidPatientOperationException(message);
        }
    }
}
