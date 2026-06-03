package com.otapp.hmis.masterdata.application.dto;

import java.util.List;

/**
 * Non-persistent response DTO for the "all prices for a supplier" aggregation
 * (build-spec §1.2 — legacy {@code SupplierItemPriceList.java} was a plain POJO wrapper).
 *
 * <p>There is NO corresponding table or entity — this is a pure response-aggregation record.
 */
public record SupplierItemPriceListDto(
        SupplierDto supplier,
        List<SupplierItemPriceDto> prices) {
}
