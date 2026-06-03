package com.otapp.hmis.registration.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Master demographic record for a hospital patient.
 *
 * <p>This aggregate root owns demographics, contact details, next-of-kin (single flat
 * record — CR-14), payment classification, and insurance plan association.
 *
 * <p>Design notes:
 * <ul>
 *   <li>{@code id} is hidden (inherited from {@link AuditableEntity}; never exposed).
 *       The public identifier is the ULID {@code uid}.
 *   <li>{@code no} (the MRN) is nullable-until-assigned: it is populated in the same
 *       transaction as the first INSERT, using {@code nextval('seq_mrno')} from V13
 *       (build-spec §2.1, CR-02).  No second flush is needed because {@code no} is not
 *       derived from the hidden surrogate {@code id}.
 *   <li>{@code searchKey} is the 5-field composite (no+first+middle+last+phone) sanitized
 *       per {@code PatientServiceImpl.java:739-744} (build-spec §2.2, CR-09).  It is
 *       computed before the first INSERT and must never be {@code null} at persistence time.
 *   <li>{@code gender} is a free-text String — no enum, no DB CHECK (CR-17).
 *   <li>Next-of-kin is exactly ONE kin, expressed as 3 flat nullable columns (CR-14).
 *   <li>Cross-module refs ({@code insurancePlanUid}, {@code businessDayUid}) are plain
 *       {@code String} columns with NO {@code @JoinColumn}/FK (ADR-0008).
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Patient.java:36-107</li>
 *   <li>MRN assignment: PatientServiceImpl.java:248-254</li>
 *   <li>searchKey build: PatientServiceImpl.java:739-744, Sanitizer.java:11-17</li>
 *   <li>Insurance consistency rule: PatientResource.java:296-305, :359-373</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patients")
public class Patient extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Identification
    // -------------------------------------------------------------------------

    /**
     * Medical Record Number.  Format: {@code MRNO/{EAT-year}/{nextval seq_mrno}}
     * (build-spec §2.1, CR-02).  Nullable-until-assigned; UNIQUE once set.
     * Legacy: Patient.java:45-47 — {@code @NotBlank @Column(unique=true) String no}.
     */
    @Column(name = "no", length = 40, unique = true)
    private String no;

    /**
     * Composite search key (no + firstName + middleName + lastName + phoneNo), sanitized.
     * NOT NULL; must be computed before first INSERT.
     * Legacy: Patient.java:48-50 — {@code @NotBlank @Column(unique=true) String searchKey}.
     * Build-spec §2.2, CR-09.
     */
    @NotBlank
    @Column(name = "search_key", columnDefinition = "TEXT", nullable = false, unique = true)
    private String searchKey;

    // -------------------------------------------------------------------------
    // Demographics (Patient.java:54-85)
    // -------------------------------------------------------------------------

    /** First name — @NotBlank (Patient.java:54-55). */
    @NotBlank
    @Column(name = "first_name", columnDefinition = "TEXT", nullable = false)
    private String firstName;

    /** Middle name — nullable (Patient.java:56). */
    @Column(name = "middle_name", columnDefinition = "TEXT")
    private String middleName;

    /** Last name — @NotBlank (Patient.java:57-58). */
    @NotBlank
    @Column(name = "last_name", columnDefinition = "TEXT", nullable = false)
    private String lastName;

    /** Date of birth — @NotNull (Patient.java:59-60). */
    @NotNull
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    /**
     * Gender — free-text @NotBlank String; no enum (CR-17, Patient.java:61-62).
     * DB has no CHECK constraint; Bean Validation enforces non-blank only.
     */
    @NotBlank
    @Column(name = "gender", length = 20, nullable = false)
    private String gender;

    // -------------------------------------------------------------------------
    // Patient classification
    // -------------------------------------------------------------------------

    /**
     * Patient type (Patient.java:63-64, CR-11).
     * Vocabulary: OUTPATIENT / OUTSIDER / INPATIENT / DECEASED.
     * Defaults to OUTPATIENT at registration (PatientResource.java:410-414).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private PatientType type = PatientType.OUTPATIENT;

    // -------------------------------------------------------------------------
    // Payment / insurance
    // -------------------------------------------------------------------------

    /**
     * Payment method (Patient.java:68-69, CR-10).
     * CASH or INSURANCE only — DEBIT/CREDIT/MOBILE are DRIFT (rejected).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20, nullable = false)
    private PaymentType paymentType;

    /**
     * Insurance membership number (Patient.java:70).
     * Default empty-string mirrors legacy.  Non-blank required when paymentType=INSURANCE
     * (DB rule: ck_patients_insurance_consistency).
     */
    @Column(name = "membership_no", length = 100)
    private String membershipNo = "";

    // -------------------------------------------------------------------------
    // Contact details (Patient.java:74-79)
    // -------------------------------------------------------------------------

    /** Phone number (Patient.java:74). */
    @Column(name = "phone_no", length = 40)
    private String phoneNo;

    /** Postal or physical address (Patient.java:75). */
    @Column(name = "address", length = 400)
    private String address;

    /** Email address (Patient.java:76). */
    @Column(name = "email", length = 120)
    private String email;

    /** Nationality (Patient.java:77). */
    @Column(name = "nationality", length = 80)
    private String nationality;

    /** National identity document number (Patient.java:78). */
    @Column(name = "national_id", length = 60)
    private String nationalId;

    /** Passport number (Patient.java:79). */
    @Column(name = "passport_no", length = 60)
    private String passportNo;

    // -------------------------------------------------------------------------
    // Next-of-kin — single flat record (Patient.java:83-85, CR-14)
    // Legacy has NO child kin entity; exactly ONE kin per patient.
    // -------------------------------------------------------------------------

    /** Full name of next-of-kin (Patient.java:83). */
    @Column(name = "kin_full_name", length = 200)
    private String kinFullName;

    /** Relationship of next-of-kin to patient (Patient.java:84). */
    @Column(name = "kin_relationship", length = 80)
    private String kinRelationship;

    /** Phone number of next-of-kin (Patient.java:85). */
    @Column(name = "kin_phone_no", length = 40)
    private String kinPhoneNo;

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /** Whether the patient record is active (Patient.java:89). Default {@code true}. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    // -------------------------------------------------------------------------
    // Cross-module loose refs (ADR-0008: plain String, no FK, no @JoinColumn)
    // -------------------------------------------------------------------------

    /**
     * Loose ref to the insurance plan in the masterdata module (no FK).
     * {@code null} for CASH patients; non-{@code null} for INSURANCE patients.
     * DB rule: ck_patients_insurance_consistency.
     * Legacy: Patient.java:97-100 — @ManyToOne InsurancePlan; replaced with loose uid ref.
     */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    /**
     * Loose ref to the open business day at the time of registration.
     * Sourced from {@code BusinessDayService.currentUid()} (ADR-0009 §7).
     * Legacy: Patient.java:104-106 — {@code created_on_day_id}; replaced with loose uid ref.
     */
    @Column(name = "business_day_uid", length = 26, nullable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Business constructor.  The caller is responsible for supplying the already-computed
     * {@code no} (MRN from {@code seq_mrno}) and {@code searchKey} (from the verbatim
     * 5-field composition — build-spec §2.2, CR-09).  These are C2/C3 concerns;
     * C1 accepts pre-computed values.
     *
     * @param no             MRN string ({@code MRNO/{year}/{seq}}) — computed before insert
     * @param searchKey      sanitized composite search key — computed before insert
     * @param firstName      first name
     * @param middleName     middle name (nullable)
     * @param lastName       last name
     * @param dateOfBirth    date of birth
     * @param gender         free-text gender string (no enum — CR-17)
     * @param type           patient type; defaults to OUTPATIENT if null
     * @param paymentType    payment method (CASH or INSURANCE)
     * @param membershipNo   insurance membership number (empty-string for CASH)
     * @param phoneNo        phone number (nullable)
     * @param insurancePlanUid   loose uid ref to insurance plan (null for CASH)
     * @param businessDayUid  loose uid ref to the current open business day
     */
    public Patient(String no, String searchKey,
                   String firstName, String middleName, String lastName,
                   LocalDate dateOfBirth, String gender,
                   PatientType type, PaymentType paymentType, String membershipNo,
                   String phoneNo,
                   String insurancePlanUid, String businessDayUid) {
        this.no = no;
        this.searchKey = searchKey;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.type = type != null ? type : PatientType.OUTPATIENT;
        this.paymentType = paymentType;
        this.membershipNo = membershipNo != null ? membershipNo : "";
        this.phoneNo = phoneNo;
        this.insurancePlanUid = insurancePlanUid;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Domain methods (intention-revealing mutations)
    // -------------------------------------------------------------------------

    /**
     * Flip the patient's type between OUTPATIENT and OUTSIDER.
     * Guards (INPATIENT blocked, DECEASED blocked) are enforced by the application service.
     * Legacy: PatientResource.java:421-495; PatientServiceImpl (change_type flow).
     */
    public void changeType(PatientType newType) {
        this.type = newType;
    }

    /**
     * Flip the patient's payment classification.
     * INSURANCE requires plan and membership; CASH collapses plan to null.
     * Guard against "open work" (unpaid bills) enforced by the application service (CR-03).
     * Legacy: PatientResource.java:368-373.
     */
    public void changePaymentType(PaymentType newPaymentType,
                                  String insurancePlanUid, String membershipNo) {
        this.paymentType = newPaymentType;
        if (newPaymentType == PaymentType.CASH) {
            this.insurancePlanUid = null;
            this.membershipNo = "";
        } else {
            this.insurancePlanUid = insurancePlanUid;
            this.membershipNo = membershipNo != null ? membershipNo : "";
        }
    }

    /**
     * Update mutable demographic fields (name, contact, kin).
     * Payment type, MRN, and type are NOT updated here — they have dedicated endpoints
     * (build-spec §8 C4).
     */
    public void updateDemographics(String firstName, String middleName, String lastName,
                                   LocalDate dateOfBirth, String gender,
                                   String phoneNo, String address, String email,
                                   String nationality, String nationalId, String passportNo,
                                   String kinFullName, String kinRelationship, String kinPhoneNo,
                                   String searchKey) {
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.phoneNo = phoneNo;
        this.address = address;
        this.email = email;
        this.nationality = nationality;
        this.nationalId = nationalId;
        this.passportNo = passportNo;
        this.kinFullName = kinFullName;
        this.kinRelationship = kinRelationship;
        this.kinPhoneNo = kinPhoneNo;
        this.searchKey = searchKey;
    }

    /** Deactivate the patient record. */
    public void deactivate() {
        this.active = false;
    }

    /** Reactivate the patient record. */
    public void activate() {
        this.active = true;
    }
}
