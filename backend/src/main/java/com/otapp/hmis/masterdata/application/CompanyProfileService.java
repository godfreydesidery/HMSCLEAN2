package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.CompanyProfileDto;
import com.otapp.hmis.masterdata.domain.CompanyProfile;
import com.otapp.hmis.masterdata.domain.CompanyProfileRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company-profile read service (increment-00 vertical slice). Records a {@code READ} audit row on
 * every access (ADR-0007); the write of that row is why this method holds a (read-write) transaction.
 * Constructor injection is Lombok-generated (DIRECTIVE 1).
 */
@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    private final CompanyProfileRepository repository;
    private final CompanyProfileMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public CompanyProfileDto current() {
        CompanyProfile profile = repository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new NotFoundException("No company profile is configured"));
        auditRecorder.record("masterdata.CompanyProfile", profile.getUid(), AuditAction.READ);
        return mapper.toDto(profile);
    }
}
