package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.SupplierRepository;
import com.otapp.hmis.masterdata.lookup.SupplierLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Package-private implementation of {@link SupplierLookup} (inc-08b). */
@Service
@RequiredArgsConstructor
class SupplierLookupImpl implements SupplierLookup {

    private final SupplierRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUid(String uid) {
        return repository.findByUid(uid).isPresent();
    }
}
