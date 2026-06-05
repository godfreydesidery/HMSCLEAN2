package com.otapp.hmis.pharmacy.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link StockMovement} (inc-08a).
 *
 * <p>Append-only by application contract — this surface intentionally exposes only {@code save}
 * (inherited) and read finders; no update/delete method is provided (AC-STK-07; the precise
 * immutability mechanism is BLOCKED on security-architect).
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByPharmacyUidAndMedicineUidOrderByOccurredAtAsc(
            String pharmacyUid, String medicineUid);

    List<StockMovement> findByPharmacyMedicineOrderByOccurredAtAsc(PharmacyMedicine pharmacyMedicine);
}
