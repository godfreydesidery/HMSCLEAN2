package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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

    List<ItemMedicineCoefficient> findAllByMedicine(Medicine medicine);

    List<ItemMedicineCoefficient> findAllByItem(Item item);

    List<ItemMedicineCoefficient> findAllByOrderByItemNameAsc();
}
