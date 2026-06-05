package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.application.dto.GrnDto;
import com.otapp.hmis.inventory.application.dto.LpoDto;
import com.otapp.hmis.inventory.domain.GoodsReceivedNote;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrder;

/** Maps inventory entities to no-id response DTOs (inc-08b; ADR-0014 §1). */
public final class InventoryMapper {

    private InventoryMapper() {
    }

    public static LpoDto toLpoDto(LocalPurchaseOrder o) {
        return new LpoDto(
                o.getUid(), o.getNo(), o.getStatus().name(), o.getStatusDescription(),
                o.getStoreUid(), o.getSupplierUid(), o.getOrderDate(), o.getValidUntil(),
                o.getDetails().stream()
                        .map(d -> new LpoDto.Detail(d.getUid(), d.getItemUid(), d.getQty(), d.getPrice()))
                        .toList());
    }

    public static GrnDto toGrnDto(GoodsReceivedNote g) {
        return new GrnDto(
                g.getUid(), g.getNo(), g.getStatus().name(), g.getStatusDescription(),
                g.getStoreUid(),
                g.getLocalPurchaseOrder() != null ? g.getLocalPurchaseOrder().getUid() : null,
                g.getDetails().stream()
                        .map(d -> new GrnDto.Detail(d.getUid(), d.getItemUid(), d.getOrderedQty(),
                                d.getReceivedQty(), d.getPrice(), d.getStatus().dbValue(),
                                d.getBatches().stream()
                                        .map(b -> new GrnDto.Batch(b.getUid(), b.getBatchNo(),
                                                b.getManufacturedDate(), b.getExpiryDate(), b.getQty()))
                                        .toList()))
                        .toList());
    }
}
