package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ItemMedicineCoefficient}.
 *
 * <p>The {@code findByItemAndMedicine} query reproduces the legacy lookup key
 * {@code itemMedicineCoefficientRepository.findByItemAndMedicine(item, medicine)}
 * (ItemMedicineCoefficientRepository.java:26) used for duplicate detection.
 *
 * <p>{@code findAllByMedicine} reproduces the legacy listing
 * (ItemMedicineCoefficientRepository.java:32) used in InternalOrderResource.java:660-665
 * to enumerate candidate source items for a medicine.
 */
public interface ItemMedicineCoefficientRepository extends JpaRepository<ItemMedicineCoefficient, Long> {

    Optional<ItemMedicineCoefficient> findByUid(String uid);

    Optional<ItemMedicineCoefficient> findByItemAndMedicine(Item item, Medicine medicine);

    /**
     * The coefficient for an (item, medicine) pair keyed by their public uids — the inc-08b
     * {@code CoefficientLookup} cross-module seam (pharmacy↔store conversion
     * {@code pharmacySKUQty = storeSKUQty * coefficient}; StoreToPharmacyTOServiceImpl.java:417-424).
     */
    @Query("""
            SELECT c FROM ItemMedicineCoefficient c
            WHERE c.item.uid = :itemUid AND c.medicine.uid = :medicineUid
            """)
    Optional<ItemMedicineCoefficient> findByItemUidAndMedicineUid(
            @Param("itemUid") String itemUid, @Param("medicineUid") String medicineUid);

    List<ItemMedicineCoefficient> findAllByMedicine(Medicine medicine);

    List<ItemMedicineCoefficient> findAllByItem(Item item);

    List<ItemMedicineCoefficient> findAllByOrderByItemNameAsc();
}
