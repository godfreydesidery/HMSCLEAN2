package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.ItemRepository;
import com.otapp.hmis.masterdata.lookup.ItemLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Package-private implementation of {@link ItemLookup} (inc-08b). Mirrors {@code MedicineLookupImpl}. */
@Service
@RequiredArgsConstructor
class ItemLookupImpl implements ItemLookup {

    private final ItemRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUid(String uid) {
        return repository.findByUid(uid).isPresent();
    }
}
