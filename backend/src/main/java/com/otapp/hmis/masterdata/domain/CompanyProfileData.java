package com.otapp.hmis.masterdata.domain;

import java.math.BigDecimal;

/**
 * Domain value object carrying all mutable fields of {@link CompanyProfile}.
 *
 * <p>Used as the parameter to {@link CompanyProfile#update(CompanyProfileData)} and
 * {@link CompanyProfile#create(CompanyProfileData)} to keep those methods within the
 * S107 parameter-count limit while preserving explicit, readable field assignments.
 *
 * <p>The application layer assembles this record from the inbound
 * {@code CompanyProfileRequest} DTO before calling the domain method — the domain
 * never sees the HTTP DTO directly (ADR-0014 §3).
 */
public record CompanyProfileData(
        // V1 fields
        String name,
        String address,
        String phone,
        // Identity / tax
        String contactName,
        byte[] logo,
        String tin,
        String vrn,
        // Address block (CompanyProfile.java:42-49)
        String physicalAddress,
        String postCode,
        String postAddress,
        String telephone,
        String mobile,
        String email,
        String fax,
        String website,
        // Bank block 1 (CompanyProfile.java:51-58)
        String bankAccountName,
        String bankPhysicalAddress,
        String bankPostCode,
        String bankPostAddress,
        String bankName,
        String bankAccountNo,
        // Bank block 2 (CompanyProfile.java:59-64)
        String bankAccountName2,
        String bankPhysicalAddress2,
        String bankPostCode2,
        String bankPostAddress2,
        String bankName2,
        String bankAccountNo2,
        // Bank block 3 (CompanyProfile.java:65-70)
        String bankAccountName3,
        String bankPhysicalAddress3,
        String bankPostCode3,
        String bankPostAddress3,
        String bankName3,
        String bankAccountNo3,
        // Notes
        String quotationNotes,
        String salesInvoiceNotes,
        // Config
        BigDecimal registrationFee,
        String publicPath,
        String employeePrefix
) {
}
