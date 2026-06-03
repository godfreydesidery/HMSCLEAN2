package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Single-row company reference entity (build-spec §1.5, CR-14).
 *
 * <p>Extends the increment-00 vertical-slice entity with the full legacy field set
 * (legacy {@code com.orbix.api.domain.CompanyProfile.java:34-81},
 * 05-extract-stakeholders-system §1). V1 columns {@code name/address/phone} are
 * immutable; V11 adds all remaining fields.
 *
 * <p>Single-row invariant (CR-14): at most one row is permitted. The service layer
 * enforces this and returns HTTP 409 on a second POST.
 *
 * <p>{@code registrationFee} is {@code double} in legacy — migrated to
 * {@code NUMERIC(19,2)}/{@code BigDecimal} per the pre-approved double→BigDecimal directive.
 *
 * <p>Lombok generates accessors (DIRECTIVE 1). No public setters — mutation via
 * {@link #update(CompanyProfileData)} only.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "company_profiles")
public class CompanyProfile extends AuditableEntity {

    // -----------------------------------------------------------------------
    // V1 columns (immutable from increment-00)
    // -----------------------------------------------------------------------

    @NotBlank
    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "phone", length = 40)
    private String phone;

    // -----------------------------------------------------------------------
    // V11 additions — full legacy CompanyProfile.java:34-81 field set
    // -----------------------------------------------------------------------

    /** Legacy {@code contactName} (CompanyProfile.java:36-37). */
    @Column(name = "contact_name", length = 200)
    private String contactName;

    /**
     * Company logo bytes (legacy CompanyProfile.java:38-39 — {@code @Lob byte[]}).
     * Stored as PostgreSQL {@code bytea}: a plain {@code byte[]} (NOT {@code @Lob}, which Hibernate
     * maps to {@code oid}/large-object and fails {@code ddl-auto=validate} against the {@code BYTEA} DDL).
     * Legacy decompresses on read but the call is commented out; we store raw bytes.
     */
    @Column(name = "logo")
    private byte[] logo;

    /** Tax Identification Number (CompanyProfile.java:40). */
    @Column(name = "tin", length = 80)
    private String tin;

    /** VAT Registration Number (CompanyProfile.java:41). */
    @Column(name = "vrn", length = 80)
    private String vrn;

    // Address block (CompanyProfile.java:42-49)
    @Column(name = "physical_address", length = 400)
    private String physicalAddress;

    @Column(name = "post_code", length = 20)
    private String postCode;

    @Column(name = "post_address", length = 200)
    private String postAddress;

    @Column(name = "telephone", length = 40)
    private String telephone;

    @Column(name = "mobile", length = 40)
    private String mobile;

    @Column(name = "email", length = 120)
    private String email;

    @Column(name = "fax", length = 40)
    private String fax;

    @Column(name = "website", length = 200)
    private String website;

    // Bank block 1 (CompanyProfile.java:51-58)
    @Column(name = "bank_account_name", length = 200)
    private String bankAccountName;

    @Column(name = "bank_physical_address", length = 400)
    private String bankPhysicalAddress;

    @Column(name = "bank_post_code", length = 20)
    private String bankPostCode;

    @Column(name = "bank_post_address", length = 200)
    private String bankPostAddress;

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "bank_account_no", length = 80)
    private String bankAccountNo;

    // Bank block 2 (CompanyProfile.java:59-64)
    @Column(name = "bank_account_name2", length = 200)
    private String bankAccountName2;

    @Column(name = "bank_physical_address2", length = 400)
    private String bankPhysicalAddress2;

    @Column(name = "bank_post_code2", length = 20)
    private String bankPostCode2;

    @Column(name = "bank_post_address2", length = 200)
    private String bankPostAddress2;

    @Column(name = "bank_name2", length = 200)
    private String bankName2;

    @Column(name = "bank_account_no2", length = 80)
    private String bankAccountNo2;

    // Bank block 3 (CompanyProfile.java:65-70)
    @Column(name = "bank_account_name3", length = 200)
    private String bankAccountName3;

    @Column(name = "bank_physical_address3", length = 400)
    private String bankPhysicalAddress3;

    @Column(name = "bank_post_code3", length = 20)
    private String bankPostCode3;

    @Column(name = "bank_post_address3", length = 200)
    private String bankPostAddress3;

    @Column(name = "bank_name3", length = 200)
    private String bankName3;

    @Column(name = "bank_account_no3", length = 80)
    private String bankAccountNo3;

    /** Free-text quotation notes (CompanyProfile.java:72). */
    @Column(name = "quotation_notes", columnDefinition = "TEXT")
    private String quotationNotes;

    /** Free-text sales-invoice notes (CompanyProfile.java:74). */
    @Column(name = "sales_invoice_notes", columnDefinition = "TEXT")
    private String salesInvoiceNotes;

    /**
     * Default cash-patient registration fee (CompanyProfile.java:77 — {@code double 0}).
     * Migrated to {@code NUMERIC(19,2)} per pre-approved directive.
     * Read by the patient-registration flow to set the initial bill amount.
     */
    @Column(name = "registration_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal registrationFee = BigDecimal.ZERO;

    /** Base path used for public/logo asset references (CompanyProfile.java:78). */
    @Column(name = "public_path", length = 400)
    private String publicPath;

    /**
     * Prefix string for employee numbers (CompanyProfile.java:80-81, default {@code "EMP"}).
     * Used by HR increment to format {@code EMP/<id>}.
     */
    @Column(name = "employee_prefix", length = 12)
    private String employeePrefix = "EMP";

    // -----------------------------------------------------------------------
    // Business constructor (increment-00 backward-compatible)
    // -----------------------------------------------------------------------

    public CompanyProfile(String name, String address, String phone) {
        this.name = name;
        this.address = address;
        this.phone = phone;
    }

    // -----------------------------------------------------------------------
    // Mutator — accepts a value object to keep the method within S107 limits
    // -----------------------------------------------------------------------

    /**
     * Updates all mutable fields from a {@link CompanyProfileData} value object (CR-14 PUT
     * contract). Using a data carrier avoids the S107 "too many parameters" violation while
     * keeping all field assignments explicit and readable.
     */
    public void update(CompanyProfileData d) {
        this.name = d.name();
        this.address = d.address();
        this.phone = d.phone();
        this.contactName = d.contactName();
        this.logo = d.logo();
        this.tin = d.tin();
        this.vrn = d.vrn();
        this.physicalAddress = d.physicalAddress();
        this.postCode = d.postCode();
        this.postAddress = d.postAddress();
        this.telephone = d.telephone();
        this.mobile = d.mobile();
        this.email = d.email();
        this.fax = d.fax();
        this.website = d.website();
        this.bankAccountName = d.bankAccountName();
        this.bankPhysicalAddress = d.bankPhysicalAddress();
        this.bankPostCode = d.bankPostCode();
        this.bankPostAddress = d.bankPostAddress();
        this.bankName = d.bankName();
        this.bankAccountNo = d.bankAccountNo();
        this.bankAccountName2 = d.bankAccountName2();
        this.bankPhysicalAddress2 = d.bankPhysicalAddress2();
        this.bankPostCode2 = d.bankPostCode2();
        this.bankPostAddress2 = d.bankPostAddress2();
        this.bankName2 = d.bankName2();
        this.bankAccountNo2 = d.bankAccountNo2();
        this.bankAccountName3 = d.bankAccountName3();
        this.bankPhysicalAddress3 = d.bankPhysicalAddress3();
        this.bankPostCode3 = d.bankPostCode3();
        this.bankPostAddress3 = d.bankPostAddress3();
        this.bankName3 = d.bankName3();
        this.bankAccountNo3 = d.bankAccountNo3();
        this.quotationNotes = d.quotationNotes();
        this.salesInvoiceNotes = d.salesInvoiceNotes();
        this.registrationFee = d.registrationFee() != null ? d.registrationFee() : BigDecimal.ZERO;
        this.publicPath = d.publicPath();
        this.employeePrefix = d.employeePrefix() != null ? d.employeePrefix() : "EMP";
    }

    // -----------------------------------------------------------------------
    // Static factory from CompanyProfileData (for the POST / create path)
    // -----------------------------------------------------------------------

    public static CompanyProfile create(CompanyProfileData d) {
        CompanyProfile p = new CompanyProfile(d.name(), d.address(), d.phone());
        p.update(d);
        return p;
    }
}
