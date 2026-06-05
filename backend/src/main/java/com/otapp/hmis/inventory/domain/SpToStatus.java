package com.otapp.hmis.inventory.domain;

/**
 * Store→Pharmacy TO (SPTO) lifecycle (inc-08b chunk 6; legacy StoreToPharmacyTOServiceImpl).
 * PENDING→VERIFIED→APPROVED→GOODS-ISSUED(store stock decrements here)→COMPLETED(at RN). No
 * RETURNED/REJECTED on the TO. GOODS-ISSUED via converter (hyphen).
 */
public enum SpToStatus {

    PENDING("PENDING"),
    VERIFIED("VERIFIED"),
    APPROVED("APPROVED"),
    GOODS_ISSUED("GOODS-ISSUED"),
    COMPLETED("COMPLETED");

    private final String dbValue;

    SpToStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static SpToStatus fromDbValue(String v) {
        for (SpToStatus s : values()) {
            if (s.dbValue.equals(v)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown SP-TO status: " + v);
    }
}
