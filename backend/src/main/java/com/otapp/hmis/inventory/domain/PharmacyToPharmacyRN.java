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
 * Pharmacy→Pharmacy received note (PPRN) header (inc-08b chunk 7; legacy PharmacyToPharmacyRN).
 * Two-state PENDING→COMPLETED (reuses {@link SpRnStatus}). DESTINATION (requesting) pharmacy stock
 * increments at {@code complete()} — AGGREGATE + IN card ONLY, NO destination batch (Q7 gap). Guard
 * lives here (service/entity), not the controller (the latent double-post fix).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_rns")
public class PharmacyToPharmacyRN extends AuditableEntity {

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
    @Column(name = "requesting_pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String requestingPharmacyUid;

    @NotBlank
    @Column(name = "delivering_pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String deliveringPharmacyUid;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_to_id", updatable = false)
    private PharmacyToPharmacyTO pharmacyToPharmacyTO;

    @OneToMany(mappedBy = "pharmacyToPharmacyRN", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<PharmacyToPharmacyRNDetail> details = new ArrayList<>();

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public PharmacyToPharmacyRN(String no, String requestingPharmacyUid, String deliveringPharmacyUid,
                                PharmacyToPharmacyTO to, String dayUid) {
        this.no = no;
        this.status = SpRnStatus.PENDING;
        this.statusDescription = "Received note pending";
        this.requestingPharmacyUid = requestingPharmacyUid;
        this.deliveringPharmacyUid = deliveringPharmacyUid;
        this.pharmacyToPharmacyTO = to;
        this.businessDayUid = dayUid;
    }

    public void addDetail(PharmacyToPharmacyRNDetail detail) {
        this.details.add(detail);
    }

    public void complete() {
        if (this.status != SpRnStatus.PENDING) {
            throw new InvalidPatientOperationException("Could not receive. Not a pending GRN");
        }
        this.status = SpRnStatus.COMPLETED;
        this.statusDescription = "Received note completed";
    }
}
