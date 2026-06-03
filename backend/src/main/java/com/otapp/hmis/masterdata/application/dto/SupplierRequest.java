package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for create/update of {@link com.otapp.hmis.masterdata.domain.Supplier}.
 */
public record SupplierRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String contactName,
        boolean active,
        String tin,
        String vrn,
        String termsOfContract,
        String physicalAddress,
        String postCode,
        String postAddress,
        String telephone,
        String mobile,
        String email,
        String fax,
        String bankAccountName,
        String bankPhysicalAddress,
        String bankPostCode,
        String bankPostAddress,
        String bankName,
        String bankAccountNo) {
}
