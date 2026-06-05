package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.ItemMedicineCoefficient;
import com.otapp.hmis.masterdata.domain.ItemMedicineCoefficientRepository;
import com.otapp.hmis.masterdata.lookup.CoefficientLookup;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Package-private implementation of {@link CoefficientLookup} (inc-08b). */
@Service
@RequiredArgsConstructor
class CoefficientLookupImpl implements CoefficientLookup {

    private final ItemMedicineCoefficientRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> coefficientFor(String itemUid, String medicineUid) {
        return repository.findByItemUidAndMedicineUid(itemUid, medicineUid)
                .map(ItemMedicineCoefficient::getCoefficient);
    }
}
