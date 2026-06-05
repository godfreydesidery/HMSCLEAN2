package com.otapp.hmis.pharmacy.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OTC sale-order line item (inc-08a chunk 4; legacy PharmacySaleOrderDetail.java:29-126).
 *
 * <p>Carries TWO independent status fields: {@code status} (fulfilment NOT-GIVEN→GIVEN, via the
 * hyphenated-string {@link OtcFulfilmentStatusConverter}) and {@code payStatus} (UNPAID→PAID). Each
 * line is billed via its OWN {@code PatientBill} (loose {@code patientBillUid}, 1:1). The medicine is
 * a loose uid (ADR-0008 §1); the parent order is an intra-module {@code @ManyToOne}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_sale_order_details")
public class PharmacySaleOrderDetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_sale_order_id", nullable = false, updatable = false)
    private PharmacySaleOrder pharmacySaleOrder;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    /** Loose 1:1 ref to this line's bill (billing module). */
    @Column(name = "patient_bill_uid", length = 26)
    private String patientBillUid;

    /** Set on dispense. */
    @Column(name = "issue_pharmacy_uid", length = 26)
    private String issuePharmacyUid;

    @Column(name = "dosage", length = 200)      private String dosage;
    @Column(name = "frequency", length = 100)   private String frequency;
    @Column(name = "route", length = 100)       private String route;
    @Column(name = "days", length = 100)        private String days;

    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty;

    @Column(name = "issued", nullable = false, precision = 19, scale = 6)
    private BigDecimal issued = BigDecimal.ZERO;

    @Column(name = "balance", nullable = false, precision = 19, scale = 6)
    private BigDecimal balance = BigDecimal.ZERO;

    /** Fulfilment status — hyphenated DB strings via converter (NOT @Enumerated). */
    @NotNull
    @Convert(converter = OtcFulfilmentStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private OtcFulfilmentStatus status = OtcFulfilmentStatus.NOT_GIVEN;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "pay_status", length = 20, nullable = false)
    private OtcPayStatus payStatus = OtcPayStatus.UNPAID;

    @Column(name = "reference", length = 500)       private String reference;
    @Column(name = "instructions", length = 1000)   private String instructions;

    @Column(name = "created_by_username", length = 80) private String createdByUsername;
    @Column(name = "created_on_day_uid", length = 26)  private String createdOnDayUid;
    @Column(name = "created_at_ts")                     private Instant createdAtTs;
    @Column(name = "approved_by_username", length = 80) private String approvedByUsername;
    @Column(name = "approved_on_day_uid", length = 26)  private String approvedOnDayUid;
    @Column(name = "approved_at_ts")                    private Instant approvedAtTs;
    @Column(name = "sold_by_username", length = 80)     private String soldByUsername;
    @Column(name = "sold_on_day_uid", length = 26)      private String soldOnDayUid;
    @Column(name = "sold_at_ts")                        private Instant soldAtTs;

    /**
     * Create a NOT-GIVEN / UNPAID line (issued=0, balance=qty).
     */
    public PharmacySaleOrderDetail(PharmacySaleOrder order, String medicineUid, BigDecimal qty,
                                   String dosage, String frequency, String route, String days,
                                   String actorUsername, String dayUid, Instant now) {
        this.pharmacySaleOrder = order;
        this.medicineUid = medicineUid;
        this.qty = qty;
        this.issued = BigDecimal.ZERO;
        this.balance = qty;
        this.status = OtcFulfilmentStatus.NOT_GIVEN;
        this.payStatus = OtcPayStatus.UNPAID;
        this.dosage = dosage;
        this.frequency = frequency;
        this.route = route;
        this.days = days;
        this.createdByUsername = actorUsername;
        this.createdOnDayUid = dayUid;
        this.createdAtTs = now;
    }

    public void linkBill(String patientBillUid) {
        this.patientBillUid = patientBillUid;
    }

    /** Mark the line paid (BillSettledEvent seam). Stamps the sold audit. */
    public void markPaid(String actorUsername, String dayUid, Instant now) {
        this.payStatus = OtcPayStatus.PAID;
        this.soldByUsername = actorUsername;
        this.soldOnDayUid = dayUid;
        this.soldAtTs = now;
    }

    /**
     * Dispense the line: NOT-GIVEN → GIVEN, all-or-nothing (issued must equal qty). Reproduces the
     * legacy give_medicine guard order (PatientResource.java:6230-6293).
     */
    public void dispense(BigDecimal issuedQty, String issuePharmacyUid,
                         String actorUsername, String dayUid, Instant now) {
        if (this.status != OtcFulfilmentStatus.NOT_GIVEN) {
            throw new InvalidPatientOperationException("Medicine already given");
        }
        if (issuedQty == null || issuedQty.compareTo(BigDecimal.ZERO) <= 0
                || issuedQty.compareTo(this.balance) < 0) {
            throw new InvalidPatientOperationException("Invalid issue value");
        }
        if (issuedQty.compareTo(this.qty) != 0) {
            throw new InvalidPatientOperationException("You can only issue the prescribed qty");
        }
        this.issued = this.qty;
        this.balance = BigDecimal.ZERO;
        this.status = OtcFulfilmentStatus.GIVEN;
        this.issuePharmacyUid = issuePharmacyUid;
        this.approvedByUsername = actorUsername;
        this.approvedOnDayUid = dayUid;
        this.approvedAtTs = now;
    }

    /** Whether this line may be deleted (PENDING/NOT-GIVEN fulfilment AND its bill UNPAID). */
    public boolean isDeletable() {
        return this.status == OtcFulfilmentStatus.NOT_GIVEN && this.payStatus == OtcPayStatus.UNPAID;
    }
}
