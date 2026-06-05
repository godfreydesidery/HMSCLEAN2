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
 * Store→Pharmacy transfer order (SPTO) header (inc-08b chunk 6; legacy StoreToPharmacyTO).
 *
 * <p>State machine: PENDING→VERIFIED→APPROVED→GOODS-ISSUED→COMPLETED (no RETURNED/REJECTED). STORE
 * stock decrements at {@code issue()} (APPROVED→GOODS-ISSUED) — the actual decrement is applied by
 * the service; this entity holds state + guards. Created only from a SUBMITTED RO.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_to_pharmacy_tos")
public class StoreToPharmacyTO extends AuditableEntity {

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
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_store_ro_id", updatable = false)
    private PharmacyToStoreRO pharmacyToStoreRO;

    @OneToMany(mappedBy = "storeToPharmacyTO", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<StoreToPharmacyTODetail> details = new ArrayList<>();

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public StoreToPharmacyTO(String no, String storeUid, String pharmacyUid,
                             PharmacyToStoreRO ro, String dayUid) {
        this.no = no;
        this.status = SpToStatus.PENDING;
        this.statusDescription = "Transfer order pending for verification";
        this.storeUid = storeUid;
        this.pharmacyUid = pharmacyUid;
        this.pharmacyToStoreRO = ro;
        this.businessDayUid = dayUid;
    }

    public void addDetail(StoreToPharmacyTODetail detail) {
        this.details.add(detail);
    }

    public void verify() {
        requireStatus(SpToStatus.PENDING, "Only pending transfer orders can be verified");
        this.status = SpToStatus.VERIFIED;
        this.statusDescription = "Transfer order awaiting for approval";
    }

    public void approve() {
        requireStatus(SpToStatus.VERIFIED, "Only verified transfer orders can be approved");
        this.status = SpToStatus.APPROVED;
        this.statusDescription = "Transfer order awaiting for issue";
    }

    /** APPROVED → GOODS-ISSUED (the service applies the store decrement). */
    public void markGoodsIssued() {
        requireStatus(SpToStatus.APPROVED, "Only approved transfer orders can be issued");
        this.status = SpToStatus.GOODS_ISSUED;
        this.statusDescription = "Goods issued";
    }

    public void markCompleted() {
        this.status = SpToStatus.COMPLETED;
        this.statusDescription = "Transfer order completed";
    }

    private void requireStatus(SpToStatus expected, String message) {
        if (this.status != expected) {
            throw new InvalidPatientOperationException(message);
        }
    }
}
