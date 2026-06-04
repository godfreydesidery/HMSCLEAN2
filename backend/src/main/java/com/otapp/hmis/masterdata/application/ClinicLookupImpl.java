package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.ClinicRepository;
import com.otapp.hmis.masterdata.lookup.ClinicLookup;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ClinicLookup} (package-private in {@code masterdata.application}).
 *
 * <p>Other modules consume the {@link ClinicLookup} interface from
 * {@code masterdata.lookup} — they never reference this class directly
 * (Spring Modulith named-interface contract, ADR-0008).
 *
 * <p>Returns only the clinic name — no Clinic domain type crosses the module boundary
 * (ADR-0008 §1).
 */
@Service
@RequiredArgsConstructor
class ClinicLookupImpl implements ClinicLookup {

    private final ClinicRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> nameByUid(String uid) {
        return repository.findByUid(uid).map(c -> c.getName());
    }
}
