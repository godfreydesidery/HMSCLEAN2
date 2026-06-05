package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.DressingRepository;
import com.otapp.hmis.masterdata.lookup.DressingLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link DressingLookup} (inc-07 07b, AC-07B-DRS-02).
 *
 * <p>Legacy citation: Dressing.java:35-49; PatientServiceImpl.java:2094.
 */
@Service
@RequiredArgsConstructor
class DressingLookupImpl implements DressingLookup {

    private final DressingRepository dressingRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isDressing(String procedureTypeUid) {
        return dressingRepository.findByProcedureTypeUid(procedureTypeUid).isPresent();
    }
}
