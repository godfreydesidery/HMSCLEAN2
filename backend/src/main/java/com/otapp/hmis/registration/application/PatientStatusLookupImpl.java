package com.otapp.hmis.registration.application;

import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.lookup.PatientStatusLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link PatientStatusLookup} (inc-07 07a SEAM-B).
 *
 * <p>Intentionally package-private in {@code registration.application} — callers depend only on
 * the {@link PatientStatusLookup} interface from {@code registration :: lookup}. Delegates to the
 * existing {@link PatientRepository#findByUid} read path.
 *
 * <p>Uses {@code readOnly = true, propagation = REQUIRED} so the read participates in the
 * caller's (inpatient) transaction if one is already active, avoiding a second round-trip.
 *
 * <p>Returns {@code false} for an unknown patient uid — the patient EXISTENCE check is handled
 * downstream by {@code BillingCommands.recordClinicalCharge} (which validates the patient as part
 * of the ward charge creation). The inpatient service documents this assumption.
 *
 * <p>Legacy citation: no equivalent in legacy — the doAdmission path simply called
 * {@code patientRepository.findById} and threw NotFoundException. The deceased guard is a
 * net-new safety hardening (CR-07-deceased-guard, owner-approved).
 */
@Service
@RequiredArgsConstructor
class PatientStatusLookupImpl implements PatientStatusLookup {

    private final PatientRepository patientRepository;

    /**
     * {@inheritDoc}
     *
     * <p>Resolves via {@link PatientRepository#findByUid}. An absent patient returns {@code false}
     * (no DECEASED status → admission is not blocked on the deceased guard).
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public boolean isDeceased(String patientUid) {
        return patientRepository.findByUid(patientUid)
                .map(p -> p.getType() == PatientType.DECEASED)
                .orElse(false);
    }
}
