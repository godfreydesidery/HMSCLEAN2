package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.ConsumableRepository;
import com.otapp.hmis.masterdata.lookup.ConsumableLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link ConsumableLookup} (inc-07 07c).
 *
 * <p>Legacy citation: ConsumableRepository.java; PatientServiceImpl.java:2259-2262.
 * Mirrors {@link DressingLookupImpl} exactly.
 */
@Service
@RequiredArgsConstructor
class ConsumableLookupImpl implements ConsumableLookup {

    private final ConsumableRepository consumableRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isConsumable(String medicineUid) {
        return consumableRepository.findByMedicineUid(medicineUid).isPresent();
    }
}
