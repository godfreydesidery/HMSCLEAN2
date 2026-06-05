package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.MedicineRepository;
import com.otapp.hmis.masterdata.lookup.MedicinePriceLookup;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link MedicinePriceLookup} (package-private in {@code masterdata.application}).
 *
 * <p>Other modules consume the {@link MedicinePriceLookup} interface from
 * {@code masterdata.lookup} — they never reference this class directly
 * (Spring Modulith named-interface contract, ADR-0008).
 *
 * <p>Returns {@code medicines.price} — the cash dispensing price field directly on the
 * {@code Medicine} entity. Used by the OTC flat-cash bill path (Q9).
 *
 * <p>Legacy citation: PatientServiceImpl.java:3395-3442 (reads {@code medicine.price} for OTC bill).
 */
@Service
@RequiredArgsConstructor
class MedicinePriceLookupImpl implements MedicinePriceLookup {

    private final MedicineRepository repository;

    @Override
    @Transactional(readOnly = true)
    public BigDecimal priceOf(String medicineUid) {
        return repository.findByUid(medicineUid)
                .map(medicine -> medicine.getPrice())
                .orElseThrow(() -> new NotFoundException("Medicine not found: " + medicineUid));
    }
}
