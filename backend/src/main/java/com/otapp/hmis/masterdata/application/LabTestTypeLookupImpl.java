package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.LabTestTypeRepository;
import com.otapp.hmis.masterdata.lookup.LabTestTypeLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link LabTestTypeLookup} (package-private in {@code masterdata.application}).
 *
 * <p>Other modules consume the {@link LabTestTypeLookup} interface from
 * {@code masterdata.lookup} — they never reference this class directly
 * (Spring Modulith named-interface contract, ADR-0008).
 *
 * <p>The check is a simple existence query on the uid — no data is returned across the
 * module boundary (ADR-0008 §1: cross-module refs are loose uids only).
 *
 * <p>Mirrors the pattern of {@link DiagnosisTypeLookupImpl}.
 */
@Service
@RequiredArgsConstructor
class LabTestTypeLookupImpl implements LabTestTypeLookup {

    private final LabTestTypeRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUid(String uid) {
        return repository.findByUid(uid).isPresent();
    }
}
