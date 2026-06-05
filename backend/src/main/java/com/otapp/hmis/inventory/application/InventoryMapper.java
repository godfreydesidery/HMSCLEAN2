package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.application.dto.GrnDto;
import com.otapp.hmis.inventory.application.dto.LpoDto;
import com.otapp.hmis.inventory.application.dto.TransferDto;
import com.otapp.hmis.inventory.domain.GoodsReceivedNote;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrder;
import com.otapp.hmis.inventory.domain.PharmacyToStoreRO;
import com.otapp.hmis.inventory.domain.StoreToPharmacyRN;
import com.otapp.hmis.inventory.domain.StoreToPharmacyTO;

/** Maps inventory entities to no-id response DTOs (inc-08b; ADR-0014 §1). */
public final class InventoryMapper {

    private InventoryMapper() {
    }

    // ---- pharmacy<->store transfer (chunk 6) ----

    public static TransferDto toRoDto(PharmacyToStoreRO ro) {
        return new TransferDto(ro.getUid(), ro.getNo(), "RO", ro.getStatus().dbValue(),
                ro.getStatusDescription(), ro.getPharmacyUid(), ro.getStoreUid(),
                ro.getDetails().stream()
                        .map(d -> new TransferDto.Line(d.getUid(), null, d.getMedicineUid(),
                                d.getOrderedQty(), null, null))
                        .toList());
    }

    public static TransferDto toToDto(StoreToPharmacyTO to) {
        return new TransferDto(to.getUid(), to.getNo(), "TO", to.getStatus().dbValue(),
                to.getStatusDescription(), to.getPharmacyUid(), to.getStoreUid(),
                to.getDetails().stream()
                        .map(d -> new TransferDto.Line(d.getUid(), d.getItemUid(), d.getMedicineUid(),
                                d.getOrderedPharmacySkuQty(), d.getTransferedStoreSkuQty(),
                                d.getTransferedPharmacySkuQty()))
                        .toList());
    }

    public static TransferDto toRnDto(StoreToPharmacyRN rn) {
        return new TransferDto(rn.getUid(), rn.getNo(), "RN", rn.getStatus().name(),
                rn.getStatusDescription(), rn.getPharmacyUid(), rn.getStoreUid(),
                rn.getDetails().stream()
                        .map(d -> new TransferDto.Line(d.getUid(), d.getItemUid(), d.getMedicineUid(),
                                d.getOrderedPharmacySkuQty(), d.getReceivedStoreSkuQty(),
                                d.getReceivedPharmacySkuQty()))
                        .toList());
    }

    // ---- pharmacy<->pharmacy transfer (chunk 7) — requesting=pharmacyUid, delivering=storeUid slot ----

    public static TransferDto toPpRoDto(com.otapp.hmis.inventory.domain.PharmacyToPharmacyRO ro) {
        return new TransferDto(ro.getUid(), ro.getNo(), "RO", ro.getStatus().dbValue(),
                ro.getStatusDescription(), ro.getRequestingPharmacyUid(), ro.getDeliveringPharmacyUid(),
                ro.getDetails().stream()
                        .map(d -> new TransferDto.Line(d.getUid(), null, d.getMedicineUid(),
                                d.getOrderedQty(), null, null))
                        .toList());
    }

    public static TransferDto toPpToDto(com.otapp.hmis.inventory.domain.PharmacyToPharmacyTO to) {
        return new TransferDto(to.getUid(), to.getNo(), "TO", to.getStatus().dbValue(),
                to.getStatusDescription(), to.getRequestingPharmacyUid(), to.getDeliveringPharmacyUid(),
                to.getDetails().stream()
                        .map(d -> new TransferDto.Line(d.getUid(), null, d.getMedicineUid(),
                                d.getOrderedQty(), d.getTransferedQty(), d.getTransferedQty()))
                        .toList());
    }

    public static TransferDto toPpRnDto(com.otapp.hmis.inventory.domain.PharmacyToPharmacyRN rn) {
        return new TransferDto(rn.getUid(), rn.getNo(), "RN", rn.getStatus().name(),
                rn.getStatusDescription(), rn.getRequestingPharmacyUid(), rn.getDeliveringPharmacyUid(),
                rn.getDetails().stream()
                        .map(d -> new TransferDto.Line(d.getUid(), null, d.getMedicineUid(),
                                d.getOrderedQty(), d.getReceivedQty(), d.getReceivedQty()))
                        .toList());
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
