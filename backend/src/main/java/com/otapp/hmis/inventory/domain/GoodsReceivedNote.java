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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Goods Received Note header (inc-08b; legacy GoodsReceivedNote + GoodsReceivedNoteServiceImpl).
 *
 * <p>Header lifecycle is ONLY PENDING → APPROVED (legacy :77,194). One GRN per LPO (V41 unique).
 * {@code approve()} guards PENDING + every detail VERIFIED; the stock effects (store credit, batch
 * copy, Purchase ledger, LPO→RECEIVED) are orchestrated by the service in one transaction.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "goods_received_notes")
public class GoodsReceivedNote extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private GrnStatus status = GrnStatus.PENDING;

    @Column(name = "status_description", length = 200)
    private String statusDescription;

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    /** Intra-module 1:1 to the LPO (nullable — legacy allows a GRN without an LPO). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_purchase_order_id", updatable = false)
    private LocalPurchaseOrder localPurchaseOrder;

    @OneToMany(mappedBy = "goodsReceivedNote", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<GoodsReceivedNoteDetail> details = new ArrayList<>();

    @Column(name = "approved_by_username", length = 80) private String approvedByUsername;
    @Column(name = "approved_on_day_uid", length = 26)  private String approvedOnDayUid;
    @Column(name = "approved_at_ts")                    private Instant approvedAtTs;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public GoodsReceivedNote(String no, String storeUid, LocalPurchaseOrder lpo, String dayUid) {
        this.no = no;
        this.status = GrnStatus.PENDING;
        this.statusDescription = "GRN Pending for verification";
        this.storeUid = storeUid;
        this.localPurchaseOrder = lpo;
        this.businessDayUid = dayUid;
    }

    public void addDetail(GoodsReceivedNoteDetail detail) {
        this.details.add(detail);
    }

    /**
     * Guard + flip to APPROVED. Stock effects are applied by the service (this only validates state
     * and stamps the audit). Legacy GoodsReceivedNoteServiceImpl.java:110-132.
     */
    public void approve(String actor, String dayUid, Instant now) {
        if (this.status != GrnStatus.PENDING) {
            throw new InvalidPatientOperationException("Not a pending GRN");
        }
        if (details.isEmpty()
                || !details.stream().allMatch(d -> d.getStatus() == GrnDetailStatus.VERIFIED)) {
            throw new InvalidPatientOperationException(
                    "All the items in the GRN must be verified before approving");
        }
        this.status = GrnStatus.APPROVED;
        this.statusDescription = "GRN Approved";
        this.approvedByUsername = actor;
        this.approvedOnDayUid = dayUid;
        this.approvedAtTs = now;
    }
}
