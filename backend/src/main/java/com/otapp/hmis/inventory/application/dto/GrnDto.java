package com.otapp.hmis.inventory.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** GRN response (inc-08b; no id, ADR-0014 §1). */
public record GrnDto(
        String uid,
        String no,
        String status,
        String statusDescription,
        String storeUid,
        String localPurchaseOrderUid,
        List<Detail> details
) {
    public record Detail(
            String uid,
            String itemUid,
            BigDecimal orderedQty,
            BigDecimal receivedQty,
            BigDecimal price,
            String status,
            List<Batch> batches
    ) {
    }

    public record Batch(
            String uid,
            String batchNo,
            LocalDate manufacturedDate,
            LocalDate expiryDate,
            BigDecimal qty
    ) {
    }
}
