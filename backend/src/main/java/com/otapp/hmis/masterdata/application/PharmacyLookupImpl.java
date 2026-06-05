package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.PharmacyRepository;
import com.otapp.hmis.masterdata.lookup.PharmacyLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link PharmacyLookup} (inc-08). Mirrors
 * {@link MedicineLookupImpl}: a uid existence check, no data across the boundary (ADR-0008 §1).
 */
@Service
@RequiredArgsConstructor
class PharmacyLookupImpl implements PharmacyLookup {

    private final PharmacyRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUid(String uid) {
        return repository.findByUid(uid).isPresent();
    }
}
