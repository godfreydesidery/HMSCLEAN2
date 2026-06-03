package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.Supplier} (build-spec §1.2).
 * Addressed by {@code uid}; carries NO {@code id} field (ADR-0014 §1).
 */
public record SupplierDto(
        String uid,
        String code,
        String name,
        String contactName,
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
