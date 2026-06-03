package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.domain.Clinician;
import com.otapp.hmis.iam.domain.ClinicianRepository;
import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.iam.lookup.ClinicianAffiliationService;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link ClinicianAffiliationService} (CR-08, build-spec §5.2).
 *
 * <p>Operates on the {@code clinician_clinic_uids} @ElementCollection via the domain methods
 * on {@link Clinician}. All affiliation mutations are addressed by USER uid so callers
 * (masterdata module) do not need to know internal iam domain shapes.
 *
 * <p>The iam module stores opaque clinic-uid strings — no FK to masterdata.Clinic — keeping
 * the module boundary clean (no iam→masterdata import; ModularityTest and IamNoEntityLeakArchTest
 * remain green).
 */
@Service
@RequiredArgsConstructor
class ClinicianAffiliationServiceImpl implements ClinicianAffiliationService {

    private final UserRepository userRepository;
    private final ClinicianRepository clinicianRepository;

    @Override
    @Transactional
    public void affiliateClinic(String userUid, String clinicUid) {
        Clinician clinician = findClinicianByUserUid(userUid);
        clinician.affiliateClinic(clinicUid);
        // JPA dirty-checking flushes the @ElementCollection update automatically
    }

    @Override
    @Transactional
    public void removeClinicAffiliation(String userUid, String clinicUid) {
        // Idempotent — if the user has no extension or affiliation doesn't exist, silently no-op
        Optional<User> userOpt = userRepository.findByUid(userUid);
        if (userOpt.isEmpty()) {
            return;
        }
        clinicianRepository.findByUser(userOpt.get())
                .ifPresent(c -> c.removeClinic(clinicUid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> clinicUidsOf(String userUid) {
        return userRepository.findByUid(userUid)
                .flatMap(clinicianRepository::findByUser)
                .map(c -> List.copyOf(c.getClinicUids()))
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> clinicianUserUidsForClinic(String clinicUid) {
        return clinicianRepository.findAllByClinicUid(clinicUid).stream()
                .map(Clinician::getUser)
                .filter(u -> u != null)
                .map(User::getUid)
                .toList();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the Clinician extension for the given user uid.
     * Throws NotFoundException if either the User or its Clinician extension is absent.
     */
    private Clinician findClinicianByUserUid(String userUid) {
        User user = userRepository.findByUid(userUid)
                .orElseThrow(() -> new NotFoundException("User not found: " + userUid));
        return clinicianRepository.findByUser(user)
                .orElseThrow(() -> new NotFoundException(
                        "No Clinician extension for user: " + userUid +
                        " — user must hold the CLINICIAN role"));
    }
}
