package com.otapp.hmis.registration.application;

import com.otapp.hmis.registration.application.dto.LastVisitDto;
import com.otapp.hmis.registration.application.dto.PatientDto;
import com.otapp.hmis.registration.application.dto.PatientSearchResult;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.Visit;
import com.otapp.hmis.registration.domain.VisitRepository;
import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side patient queries (build-spec §6 — search, get-by-uid, last-visit). Reads are
 * authenticated-only (NO privilege gate — CR-04 parity); the controller enforces no
 * {@code @PreAuthorize}. {@code lastVisitAt} is enriched from the most-recent {@link Visit}
 * (explicit ORDER BY created_at DESC — CR-08).
 */
@Service
@RequiredArgsConstructor
public class PatientQueryService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final PatientMapper patientMapper;

    /**
     * Paginated, case-insensitive search across no/names/phone/membership (REG-1; build-spec §6).
     *
     * @param query the substring to match (null/blank matches all)
     * @param page  zero-based page index
     * @param size  page size
     */
    @Transactional(readOnly = true)
    public PatientSearchResult search(String query, int page, int size) {
        String q = query != null ? query : "";
        Page<Patient> result = patientRepository.search(q, PageRequest.of(page, size));
        List<PatientDto> content = result.getContent().stream().map(this::toDtoWithLastVisit).toList();
        return new PatientSearchResult(
                content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** Fetch one patient by uid (incl. lastVisitAt). 404 if absent. */
    @Transactional(readOnly = true)
    public PatientDto getByUid(String uid) {
        return toDtoWithLastVisit(requirePatient(uid));
    }

    /** The patient's last-visit timestamp (CR-08). 404 if the patient is absent. */
    @Transactional(readOnly = true)
    public LastVisitDto lastVisit(String uid) {
        return new LastVisitDto(lastVisitAt(requirePatient(uid)));
    }

    // -------------------------------------------------------------------------

    private PatientDto toDtoWithLastVisit(Patient patient) {
        return patientMapper.toDto(patient, lastVisitAt(patient));
    }

    private Instant lastVisitAt(Patient patient) {
        return visitRepository.findFirstByPatientOrderByCreatedAtDesc(patient)
                .map(AuditableEntity::getCreatedAt)
                .orElse(null);
    }

    private Patient requirePatient(String uid) {
        return patientRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + uid));
    }
}
