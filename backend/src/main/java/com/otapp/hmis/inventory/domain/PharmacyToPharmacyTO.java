package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Pharmacy→Pharmacy transfer order (PPTO) header (inc-08b chunk 7; legacy PharmacyToPharmacyTO).
 * Reuses 5-state {@link SpToStatus}. SOURCE (delivering) pharmacy stock decrements at {@code issue()}
 * (the service applies it). PPTO replaces the legacy 'SPT' collision prefix (CR-10).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_tos")
public class PharmacyToPharmacyTO extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    @NotNull
    @Convert(converter = SpToStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private SpToStatus status = SpToStatus.PENDING;

    @Column(name = "status_description", length = 200)
    private String statusDescription;

    @NotBlank
    @Column(name = "requesting_pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String requestingPharmacyUid;

    @NotBlank
    @Column(name = "delivering_pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String deliveringPharmacyUid;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_ro_id", updatable = false)
    private PharmacyToPharmacyRO pharmacyToPharmacyRO;

    @OneToMany(mappedBy = "pharmacyToPharmacyTO", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<PharmacyToPharmacyTODetail> details = new ArrayList<>();

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public PharmacyToPharmacyTO(String no, String requestingPharmacyUid, String deliveringPharmacyUid,
                                PharmacyToPharmacyRO ro, String dayUid) {
        this.no = no;
        this.status = SpToStatus.PENDING;
        this.statusDescription = "Transfer order pending for verification";
        this.requestingPharmacyUid = requestingPharmacyUid;
        this.deliveringPharmacyUid = deliveringPharmacyUid;
        this.pharmacyToPharmacyRO = ro;
        this.businessDayUid = dayUid;
    }

    public void addDetail(PharmacyToPharmacyTODetail detail) {
        this.details.add(detail);
    }

    public void verify() {
        require(SpToStatus.PENDING, "Only pending transfer orders can be verified");
        this.status = SpToStatus.VERIFIED;
        this.statusDescription = "Transfer order awaiting for approval";
    }

    public void approve() {
        require(SpToStatus.VERIFIED, "Only verified transfer orders can be approved");
        this.status = SpToStatus.APPROVED;
        this.statusDescription = "Transfer order awaiting for issue";
    }

    public void markGoodsIssued() {
        require(SpToStatus.APPROVED, "Only approved transfer orders can be issued");
        this.status = SpToStatus.GOODS_ISSUED;
        this.statusDescription = "Goods issued";
    }

    public void markCompleted() {
        this.status = SpToStatus.COMPLETED;
        this.statusDescription = "Transfer order completed";
    }

    private void require(SpToStatus expected, String message) {
        if (this.status != expected) {
            throw new InvalidPatientOperationException(message);
        }
    }
}
