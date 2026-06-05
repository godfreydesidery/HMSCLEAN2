package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Store→Pharmacy received note (PGRN) header (inc-08b chunk 6; legacy StoreToPharmacyRN).
 *
 * <p>Two-state machine PENDING→COMPLETED. PHARMACY stock increments at {@code complete()}
 * (applied by the service via {@code pharmacy::api}). <strong>The PENDING→COMPLETED guard lives HERE
 * (entity/service), NOT in the controller</strong> — the build-spec correction of the legacy latent
 * double-post bug where the guard was only in the controller (InternalOrderResource.java:725-748).
 * PENDING→APPROVED status values are valid identifiers → {@code @Enumerated}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_to_pharmacy_rns")
public class StoreToPharmacyRN extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SpRnStatus status = SpRnStatus.PENDING;

    @Column(name = "status_description", length = 200)
    private String statusDescription;

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_to_pharmacy_to_id", updatable = false)
    private StoreToPharmacyTO storeToPharmacyTO;

    @OneToMany(mappedBy = "storeToPharmacyRN", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<StoreToPharmacyRNDetail> details = new ArrayList<>();

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public StoreToPharmacyRN(String no, String pharmacyUid, String storeUid,
                             StoreToPharmacyTO to, String dayUid) {
        this.no = no;
        this.status = SpRnStatus.PENDING;
        this.statusDescription = "Received note pending";
        this.pharmacyUid = pharmacyUid;
        this.storeUid = storeUid;
        this.storeToPharmacyTO = to;
        this.businessDayUid = dayUid;
    }

    public void addDetail(StoreToPharmacyRNDetail detail) {
        this.details.add(detail);
    }

    /**
     * PENDING → COMPLETED. Guard lives here (service/entity), not the controller — the latent
     * double-post fix. The pharmacy stock credit is applied by the service.
     */
    public void complete() {
        if (this.status != SpRnStatus.PENDING) {
            throw new InvalidPatientOperationException("Only a pending received note can be completed");
        }
        this.status = SpRnStatus.COMPLETED;
        this.statusDescription = "Received note completed";
    }
}
