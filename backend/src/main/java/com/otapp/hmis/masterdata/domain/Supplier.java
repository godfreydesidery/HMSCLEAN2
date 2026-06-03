package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Supplier master (legacy {@code com.orbix.api.domain.Supplier}, Supplier.java:31-66).
 *
 * <p>Fields: mandatory {@code code}, {@code name}, {@code contactName} (all {@code @NotBlank}
 * in legacy); optional address block and bank block (Supplier.java:44-59).
 * {@code active} defaults to {@code true} (Supplier.java:43).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "suppliers")
public class Supplier extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    /** Mandatory contact name (Supplier.java:41-42, @NotBlank). */
    @NotBlank
    @Column(name = "contact_name", length = 200, nullable = false)
    private String contactName;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    // --- Optional tax fields ---
    @Column(name = "tin", length = 40)
    private String tin;

    @Column(name = "vrn", length = 40)
    private String vrn;

    @Column(name = "terms_of_contract", columnDefinition = "TEXT")
    private String termsOfContract;

    // --- Address block ---
    @Column(name = "physical_address", length = 400)
    private String physicalAddress;

    @Column(name = "post_code", length = 40)
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

    // --- Bank block ---
    @Column(name = "bank_account_name", length = 200)
    private String bankAccountName;

    @Column(name = "bank_physical_address", length = 400)
    private String bankPhysicalAddress;

    @Column(name = "bank_post_code", length = 40)
    private String bankPostCode;

    @Column(name = "bank_post_address", length = 200)
    private String bankPostAddress;

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "bank_account_no", length = 60)
    private String bankAccountNo;

    /** Business constructor — all fields. */
    public Supplier(String code, String name, String contactName, boolean active,
                    String tin, String vrn, String termsOfContract,
                    String physicalAddress, String postCode, String postAddress,
                    String telephone, String mobile, String email, String fax,
                    String bankAccountName, String bankPhysicalAddress,
                    String bankPostCode, String bankPostAddress,
                    String bankName, String bankAccountNo) {
        this.code = code;
        this.name = name;
        this.contactName = contactName;
        this.active = active;
        this.tin = tin;
        this.vrn = vrn;
        this.termsOfContract = termsOfContract;
        this.physicalAddress = physicalAddress;
        this.postCode = postCode;
        this.postAddress = postAddress;
        this.telephone = telephone;
        this.mobile = mobile;
        this.email = email;
        this.fax = fax;
        this.bankAccountName = bankAccountName;
        this.bankPhysicalAddress = bankPhysicalAddress;
        this.bankPostCode = bankPostCode;
        this.bankPostAddress = bankPostAddress;
        this.bankName = bankName;
        this.bankAccountNo = bankAccountNo;
    }

    /** Mutates all mutable fields in one call. */
    public void update(String code, String name, String contactName, boolean active,
                       String tin, String vrn, String termsOfContract,
                       String physicalAddress, String postCode, String postAddress,
                       String telephone, String mobile, String email, String fax,
                       String bankAccountName, String bankPhysicalAddress,
                       String bankPostCode, String bankPostAddress,
                       String bankName, String bankAccountNo) {
        this.code = code;
        this.name = name;
        this.contactName = contactName;
        this.active = active;
        this.tin = tin;
        this.vrn = vrn;
        this.termsOfContract = termsOfContract;
        this.physicalAddress = physicalAddress;
        this.postCode = postCode;
        this.postAddress = postAddress;
        this.telephone = telephone;
        this.mobile = mobile;
        this.email = email;
        this.fax = fax;
        this.bankAccountName = bankAccountName;
        this.bankPhysicalAddress = bankPhysicalAddress;
        this.bankPostCode = bankPostCode;
        this.bankPostAddress = bankPostAddress;
        this.bankName = bankName;
        this.bankAccountNo = bankAccountNo;
    }
}
