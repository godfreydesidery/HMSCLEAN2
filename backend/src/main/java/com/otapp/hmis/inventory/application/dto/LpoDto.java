package com.otapp.hmis.inventory.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** LPO response (inc-08b; no id, ADR-0014 §1). */
public record LpoDto(
        String uid,
        String no,
        String status,
        String statusDescription,
        String storeUid,
        String supplierUid,
        LocalDate orderDate,
        LocalDate validUntil,
        List<Detail> details
) {
    public record Detail(String uid, String itemUid, BigDecimal qty, BigDecimal price) {
    }
}
