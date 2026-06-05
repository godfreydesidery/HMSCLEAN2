package com.otapp.hmis.pharmacy.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PharmacyMedicine} (inc-08a).
 *
 * <p>All cross-module references are loose uids (ADR-0008 §1). The aggregate row is unique per
 * (pharmacy_uid, medicine_uid) — see the V39 unique constraint.
 *
 * <p>NO pessimistic-lock finder here — the Q4 {@code PESSIMISTIC_WRITE} decrement lock is parked
 * (CR-08-Q4 + ADR-0017); the baseline uses only the inherited {@code @Version} (AC-STK-10).
 */
public interface PharmacyMedicineRepository extends JpaRepository<PharmacyMedicine, Long> {

    Optional<PharmacyMedicine> findByUid(String uid);

    Optional<PharmacyMedicine> findByPharmacyUidAndMedicineUid(String pharmacyUid, String medicineUid);

    List<PharmacyMedicine> findByPharmacyUidOrderByMedicineUidAsc(String pharmacyUid);

    boolean existsByPharmacyUidAndMedicineUid(String pharmacyUid, String medicineUid);
}
