package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Inbound request body for {@code POST} and {@code PUT} on
 * {@code /api/v1/masterdata/company-profile} (build-spec §1.5, CR-14).
 *
 * <p>Logo bytes are not part of this JSON request — logo upload is a separate
 * multipart endpoint concern and is not implemented in inc-02. All other fields
 * from the full legacy CompanyProfile field set are accepted here.
 */
public record CompanyProfileRequest(
        @NotBlank
        String name,
        String address,
        String phone,
        String contactName,
        String tin,
        String vrn,
        String physicalAddress,
        String postCode,
        String postAddress,
        String telephone,
        String mobile,
        String email,
        String fax,
        String website,
        String bankAccountName,
        String bankPhysicalAddress,
        String bankPostCode,
        String bankPostAddress,
        String bankName,
        String bankAccountNo,
        String bankAccountName2,
        String bankPhysicalAddress2,
        String bankPostCode2,
        String bankPostAddress2,
        String bankName2,
        String bankAccountNo2,
        String bankAccountName3,
        String bankPhysicalAddress3,
        String bankPostCode3,
        String bankPostAddress3,
        String bankName3,
        String bankAccountNo3,
        String quotationNotes,
        String salesInvoiceNotes,
        BigDecimal registrationFee,
        String publicPath,
        String employeePrefix
) {
}
