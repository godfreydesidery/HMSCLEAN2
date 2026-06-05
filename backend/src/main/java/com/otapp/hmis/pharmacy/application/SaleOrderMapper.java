package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.pharmacy.application.dto.SaleOrderDetailDto;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDto;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrder;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderDetail;

/** Maps OTC sale-order entities to no-id response DTOs (inc-08a chunk 4; ADR-0014 §1). */
public final class SaleOrderMapper {

    private SaleOrderMapper() {
    }

    public static SaleOrderDto toDto(PharmacySaleOrder o) {
        return new SaleOrderDto(
                o.getUid(),
                o.getNo(),
                o.getPaymentType(),
                o.getStatus().name(),
                o.getComments(),
                o.getPharmacyUid(),
                o.getPharmacistUid(),
                o.getPharmacyCustomer().getUid(),
                o.getPharmacyCustomer().getNo(),
                o.getPharmacyCustomer().getName(),
                o.getDetails().stream().map(SaleOrderMapper::toDetailDto).toList());
    }

    public static SaleOrderDetailDto toDetailDto(PharmacySaleOrderDetail d) {
        return new SaleOrderDetailDto(
                d.getUid(),
                d.getMedicineUid(),
                d.getPatientBillUid(),
                d.getIssuePharmacyUid(),
                d.getQty(),
                d.getIssued(),
                d.getBalance(),
                d.getStatus().dbValue(),
                d.getPayStatus().name(),
                d.getDosage(),
                d.getFrequency(),
                d.getRoute(),
                d.getDays());
    }
}
