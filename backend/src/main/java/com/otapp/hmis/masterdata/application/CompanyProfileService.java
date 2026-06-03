package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.CompanyProfileDto;
import com.otapp.hmis.masterdata.application.dto.CompanyProfileRequest;
import com.otapp.hmis.masterdata.domain.CompanyProfile;
import com.otapp.hmis.masterdata.domain.CompanyProfileData;
import com.otapp.hmis.masterdata.domain.CompanyProfileRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the singleton {@link CompanyProfile} (build-spec §1.5, CR-14).
 *
 * <h2>Single-row invariant (CR-14)</h2>
 * <ul>
 *   <li>{@link #create} inserts the row if none exists; throws
 *       {@link CompanyProfileExistsException} (409) if a row already exists. This is an
 *       improvement over legacy's silent {@code deleteAll()+keepOne} behaviour
 *       (CompanyProfileServiceImpl.java:36-99).</li>
 *   <li>{@link #update} mutates the single existing row; throws
 *       {@link NotFoundException} (404) if no row exists yet.</li>
 *   <li>{@link #current} reads the single row; throws {@link NotFoundException} (404)
 *       if no row exists.</li>
 * </ul>
 *
 * <p>Every mutation records an audit row within the same transaction
 * ({@code Propagation.MANDATORY} is enforced by {@link AuditRecorder}).
 * No {@code LocalDateTime.now()} / {@code Instant.now()} here (ArchUnit gate).
 */
@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    private static final String ENTITY_TYPE = "masterdata.CompanyProfile";

    private final CompanyProfileRepository repository;
    private final CompanyProfileMapper mapper;
    private final AuditRecorder auditRecorder;

    /**
     * Creates the singleton company-profile row (POST).
     *
     * @throws CompanyProfileExistsException 409 if a row already exists (CR-14)
     */
    @Transactional
    public CompanyProfileDto create(CompanyProfileRequest request) {
        if (repository.count() > 0) {
            throw new CompanyProfileExistsException();
        }
        CompanyProfileData data = mapper.toData(request);
        CompanyProfile profile = CompanyProfile.create(data);
        repository.save(profile);
        auditRecorder.record(ENTITY_TYPE, profile.getUid(), AuditAction.CREATE);
        return mapper.toDto(profile);
    }

    /**
     * Updates the singleton company-profile row (PUT).
     *
     * @throws NotFoundException 404 if no row exists yet
     */
    @Transactional
    public CompanyProfileDto update(CompanyProfileRequest request) {
        CompanyProfile profile = repository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new NotFoundException(
                        "No company profile exists; use POST to create one first."));
        CompanyProfileData data = mapper.toData(request);
        profile.update(data);
        auditRecorder.record(ENTITY_TYPE, profile.getUid(), AuditAction.UPDATE);
        return mapper.toDto(profile);
    }

    /**
     * Returns the singleton company-profile row (GET).
     *
     * <p>Records a {@code READ} audit row on every access (ADR-0007). The write of that
     * row is why this method holds a (read-write) transaction.
     *
     * @throws NotFoundException 404 if no row is configured
     */
    @Transactional
    public CompanyProfileDto current() {
        CompanyProfile profile = repository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new NotFoundException("No company profile is configured"));
        auditRecorder.record(ENTITY_TYPE, profile.getUid(), AuditAction.READ);
        return mapper.toDto(profile);
    }
}
