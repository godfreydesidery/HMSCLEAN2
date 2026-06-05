package com.otapp.hmis.inventory.domain;

/**
 * Pharmacy→Store RO (PSR) lifecycle (inc-08b chunk 6; legacy PharmacyToStoreROServiceImpl).
 * PENDING→VERIFIED→APPROVED→SUBMITTED→IN-PROCESS(TO create)→GOODS-ISSUED(TO issue)→COMPLETED(RN
 * approve); RETURNED/REJECTED only from SUBMITTED. The hyphenated values use a converter
 * ({@link PsRoStatusConverter}) since IN-PROCESS / GOODS-ISSUED are not valid identifiers.
 */
public enum PsRoStatus {

    PENDING("PENDING"),
    VERIFIED("VERIFIED"),
    APPROVED("APPROVED"),
    SUBMITTED("SUBMITTED"),
    IN_PROCESS("IN-PROCESS"),
    GOODS_ISSUED("GOODS-ISSUED"),
    COMPLETED("COMPLETED"),
    RETURNED("RETURNED"),
    REJECTED("REJECTED");

    private final String dbValue;

    PsRoStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static PsRoStatus fromDbValue(String v) {
        for (PsRoStatus s : values()) {
            if (s.dbValue.equals(v)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown PS-RO status: " + v);
    }
}
