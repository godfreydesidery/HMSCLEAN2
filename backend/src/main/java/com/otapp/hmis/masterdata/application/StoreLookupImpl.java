package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.StoreRepository;
import com.otapp.hmis.masterdata.lookup.StoreLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link StoreLookup} (inc-08, 08b). Mirrors
 * {@link MedicineLookupImpl}: uid existence check only (ADR-0008 §1).
 */
@Service
@RequiredArgsConstructor
class StoreLookupImpl implements StoreLookup {

    private final StoreRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUid(String uid) {
        return repository.findByUid(uid).isPresent();
    }
}
