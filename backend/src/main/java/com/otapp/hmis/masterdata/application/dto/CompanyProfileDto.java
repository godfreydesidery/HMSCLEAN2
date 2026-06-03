package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.CompanyProfile}
 * (build-spec §1.5, CR-14). Addressed by {@code uid}; carries NO {@code id} field
 * (ADR-0014 §1). The full legacy field set is exposed (05-extract-stakeholders-system §1).
 *
 * <p>Logo bytes are intentionally omitted from this DTO — logo is a large binary
 * served via a dedicated endpoint to avoid bloating every GET response.
 */
public record CompanyProfileDto(
        String uid,
        // V1 fields
        String name,
        String address,
        String phone,
        // Identity / tax
        String contactName,
        String tin,
        String vrn,
        // Address block
        String physicalAddress,
        String postCode,
        String postAddress,
        String telephone,
        String mobile,
        String email,
        String fax,
        String website,
        // Bank block 1
        String bankAccountName,
        String bankPhysicalAddress,
        String bankPostCode,
        String bankPostAddress,
        String bankName,
        String bankAccountNo,
        // Bank block 2
        String bankAccountName2,
        String bankPhysicalAddress2,
        String bankPostCode2,
        String bankPostAddress2,
        String bankName2,
        String bankAccountNo2,
        // Bank block 3
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
