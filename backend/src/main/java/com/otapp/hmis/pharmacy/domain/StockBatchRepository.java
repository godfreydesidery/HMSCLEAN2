package com.otapp.hmis.pharmacy.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link StockBatch} (inc-08a).
 *
 * <p><strong>FEFO selection — verbatim legacy {@code getEarlierBatch} (PatientResource.java:3338-3376;
 * Q8 baseline, AC-RX-DSP-11/AC-STK-08):</strong> the legacy logic, reproduced in the service, is:
 * <ol>
 *   <li>if ANY lot with positive remaining has a non-null expiry → consume earliest expiry first,
 *       and SILENTLY EXCLUDE all null-expiry lots ({@link #findDatedForFefo});</li>
 *   <li>else (no dated lot) → fall back to lowest id (FIFO) over the null-expiry lots
 *       ({@link #findUndatedForFefo}).</li>
 * </ol>
 * The {@code NULLS-LAST} alternative is parked (HDE/Q8) — this baseline EXCLUDES, it does not order
 * null-expiry last. Both finders are ordered {@code id ASC} as the pinned secondary sort (N8).
 *
 * <p>NO {@code @Lock(PESSIMISTIC_WRITE)} finder — the Q4 lock is parked (AC-STK-10).
 */
public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    Optional<StockBatch> findByUid(String uid);

    List<StockBatch> findByPharmacyMedicine(PharmacyMedicine pharmacyMedicine);

    /**
     * Dated lots with positive remaining for the (pharmacy, medicine) aggregate, ordered
     * earliest-expiry first then lowest-id. Used when at least one dated lot exists — null-expiry
     * lots are EXCLUDED by the {@code expiry_date IS NOT NULL} predicate (legacy exclusion, Q8).
     */
    @Query("""
            SELECT b FROM StockBatch b
            WHERE b.pharmacyMedicine = :pm
              AND b.remainingQty > 0
              AND b.expiryDate IS NOT NULL
            ORDER BY b.expiryDate ASC, b.id ASC
            """)
    List<StockBatch> findDatedForFefo(@Param("pm") PharmacyMedicine pharmacyMedicine);

    /**
     * Null-expiry lots with positive remaining, ordered lowest-id (FIFO). Used ONLY when no dated
     * lot exists (legacy fallback, Q8).
     */
    @Query("""
            SELECT b FROM StockBatch b
            WHERE b.pharmacyMedicine = :pm
              AND b.remainingQty > 0
              AND b.expiryDate IS NULL
            ORDER BY b.id ASC
            """)
    List<StockBatch> findUndatedForFefo(@Param("pm") PharmacyMedicine pharmacyMedicine);
}
