package com.otapp.hmis.pharmacy.application.dto;

import java.util.List;

/**
 * OTC sale-order response (inc-08a chunk 4). No internal id (ADR-0014 §1). {@code status} is the
 * header lifecycle enum name (PENDING/APPROVED/ARCHIVED/CANCELED).
 */
public record SaleOrderDto(
        String uid,
        String no,
        String paymentType,
        String status,
        String comments,
        String pharmacyUid,
        String pharmacistUid,
        String customerUid,
        String customerNo,
        String customerName,
        List<SaleOrderDetailDto> details
) {
}
