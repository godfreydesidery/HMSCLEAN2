package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.MedicineRepository;
import com.otapp.hmis.masterdata.lookup.MedicineLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link MedicineLookup} (package-private in {@code masterdata.application}).
 *
 * <p>Other modules consume the {@link MedicineLookup} interface from
 * {@code masterdata.lookup} — they never reference this class directly
 * (Spring Modulith named-interface contract, ADR-0008).
 *
 * <p>The check is a simple existence query on the uid — no data is returned across the
 * module boundary (ADR-0008 §1: cross-module refs are loose uids only).
 *
 * <p>Mirrors the pattern of {@link LabTestTypeLookupImpl}.
 *
 * <p>Legacy citation: Prescription.java:72-75 (medicine mandatory ref).
 */
@Service
@RequiredArgsConstructor
class MedicineLookupImpl implements MedicineLookup {

    private final MedicineRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUid(String uid) {
        return repository.findByUid(uid).isPresent();
    }
}
