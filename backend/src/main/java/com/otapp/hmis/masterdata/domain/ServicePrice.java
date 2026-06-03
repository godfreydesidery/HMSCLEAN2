package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Unified pricing matrix row (build-spec §2.1, CR-04).
 *
 * <p>Replaces the 7 legacy {@code *InsurancePlan} tables
 * ({@code consultation_insurance_plans}, {@code registration_insurance_plans},
 * {@code lab_test_type_insurance_plans}, {@code medicine_insurance_plans},
 * {@code procedure_type_insurance_plans}, {@code radiology_type_insurance_plans},
 * {@code ward_type_insurance_plans}).
 *
 * <p>Key design decisions (build-spec §2.1, 11-DECISIONS-RATIFIED):
 * <ul>
 *   <li>{@code planUid} NULL = cash row (not a DB FK; loose uid string matching
 *       {@code insurance_plans.uid}).</li>
 *   <li>{@code serviceUid} NULL = allowed ONLY for {@link ServiceKind#REGISTRATION}
 *       (plan-only keyed; CR-18: NOT the magic string "DEFAULT").</li>
 *   <li>{@code kind} uses {@link ServiceKind#WARD} (not WARD_DAY) — ward charge is
 *       per-stay (CR-04/D15, CR-12).</li>
 *   <li>{@code covered=false} rows are persisted placeholders that NEVER trigger coverage;
 *       {@link com.otapp.hmis.masterdata.application.PriceLookupImpl} queries
 *       {@code covered=TRUE} only (PatientServiceImpl.java:1799).</li>
 *   <li>{@code active} is INERT in resolve — kept for legacy fidelity
 *       (04-extract-pricing-insurance §3 item 6).</li>
 *   <li>{@code minAmount}/{@code maxAmount}/{@code currency} are NET-NEW, INERT (CR-11):
 *       stored, never consulted in resolution logic.</li>
 * </ul>
 *
 * <p>Uniqueness is enforced by the COALESCE partial-unique index
 * {@code uq_service_prices_plan_kind_svc_cur} rather than a standard {@code UNIQUE}
 * constraint, because PostgreSQL treats two NULL values as not-equal under standard
 * unique indexes. The index expression {@code COALESCE(plan_uid,''), kind,
 * COALESCE(service_uid,''), currency} ensures cash rows and REGISTRATION rows are
 * correctly deduplicated (AC-5).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "service_prices")
public class ServicePrice extends AuditableEntity {

    /**
     * Loose uid reference to {@code insurance_plans.uid}; NULL for cash rows.
     * No DB FK — the matrix is a cross-service pricing store (build-spec §2.1).
     */
    @Column(name = "plan_uid", length = 26)
    private String planUid;

    /**
     * The billable service category (stored as VARCHAR via {@code @Enumerated(STRING)}).
     * Uses WARD (not WARD_DAY) — ward charge is per-stay (CR-04/D15, CR-12).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private ServiceKind kind;

    /**
     * Loose uid referencing the specific service entity (Clinic, WardType, LabTestType,
     * Medicine, ProcedureType, RadiologyType). NULL only for {@link ServiceKind#REGISTRATION}
     * (plan-only keyed — CR-18).
     */
    @Column(name = "service_uid", length = 26)
    private String serviceUid;

    /**
     * ISO 4217 currency code (NET-NEW, INERT — CR-11). Stored but never consulted
     * during resolution. Default 'TZS' mirrors the system currency seed.
     */
    @NotNull
    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "TZS";

    /**
     * The price amount for this plan/service combination.
     * BigDecimal replaces legacy {@code double} (pre-approved).
     */
    @NotNull
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    /**
     * Whether this plan covers the service (legacy {@code covered boolean = false}).
     * Cash rows are stored with {@code covered=TRUE} by convention.
     * {@code covered=FALSE} rows are persisted placeholders — they NEVER override
     * (PatientServiceImpl.java:597-601 queries {@code findByXAndInsurancePlanAndCovered(true)}).
     */
    @Column(name = "covered", nullable = false)
    private boolean covered = false;

    /**
     * Optional minimum amount (NET-NEW, INERT — CR-11). Stored, never used in
     * {@link com.otapp.hmis.masterdata.application.PriceLookupImpl#resolve}.
     */
    @Column(name = "min_amount", precision = 19, scale = 2)
    private BigDecimal minAmount;

    /**
     * Optional maximum amount (NET-NEW, INERT — CR-11). Stored, never used in
     * {@link com.otapp.hmis.masterdata.application.PriceLookupImpl#resolve}.
     */
    @Column(name = "max_amount", precision = 19, scale = 2)
    private BigDecimal maxAmount;

    /**
     * Administrative active flag (INERT in resolution — CR-04 §4 extract item 6).
     * Kept for legacy fidelity; does not gate {@code PriceLookup.resolve}.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Business constructor — all fields.
     */
    public ServicePrice(String planUid, ServiceKind kind, String serviceUid,
                        String currency, BigDecimal amount, boolean covered,
                        BigDecimal minAmount, BigDecimal maxAmount, boolean active) {
        this.planUid = planUid;
        this.kind = kind;
        this.serviceUid = serviceUid;
        this.currency = currency != null ? currency : "TZS";
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.covered = covered;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.active = active;
    }

    /** Mutates all mutable fields in one call. */
    public void update(String planUid, ServiceKind kind, String serviceUid,
                       String currency, BigDecimal amount, boolean covered,
                       BigDecimal minAmount, BigDecimal maxAmount, boolean active) {
        this.planUid = planUid;
        this.kind = kind;
        this.serviceUid = serviceUid;
        this.currency = currency != null ? currency : "TZS";
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.covered = covered;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.active = active;
    }
}
