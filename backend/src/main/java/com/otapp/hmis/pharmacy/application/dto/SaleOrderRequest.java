package com.otapp.hmis.pharmacy.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Create-OTC-order request (inc-08a chunk 4). pharmacyUid + pharmacistUid are required, server-
 * validated (Q2). The customer is either an existing {@code customerUid} OR a new walk-in described
 * by {@code customerName}(+optional gender/phone/address). paymentType is NOT accepted — the order
 * is always CASH (Q9).
 */
public record SaleOrderRequest(
        @NotBlank String pharmacyUid,
        @NotBlank String pharmacistUid,
        String customerUid,
        String customerName,
        String customerGender,
        String customerPhoneNo,
        String customerAddress,
        String comments
) {
}
