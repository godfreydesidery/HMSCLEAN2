package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ClinicDto;
import com.otapp.hmis.masterdata.application.dto.ClinicRequest;
import com.otapp.hmis.masterdata.domain.Clinic;
import com.otapp.hmis.masterdata.domain.ClinicRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Clinic} catalog management (build-spec §1.1, §3).
 *
 * <p>Every mutation is audited via {@link AuditRecorder} within the same transaction
 * ({@code Propagation.MANDATORY} is enforced by AuditRecorder — build-spec §5.5).
 * No {@code LocalDateTime.now()} / {@code Instant.now()} called here (ArchUnit gate).
 */
@Service
@RequiredArgsConstructor
public class ClinicService {

    private final ClinicRepository repository;
    private final ClinicMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public ClinicDto create(ClinicRequest request) {
        Clinic clinic = new Clinic(
                request.code(),
                request.name(),
                request.description(),
                request.consultationFee(),
                request.active());
        repository.save(clinic);
        auditRecorder.record("masterdata.Clinic", clinic.getUid(), AuditAction.CREATE);
        return mapper.toDto(clinic);
    }

    @Transactional
    public ClinicDto update(String uid, ClinicRequest request) {
        Clinic clinic = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + uid));
        clinic.update(
                request.code(),
                request.name(),
                request.description(),
                request.consultationFee(),
                request.active());
        auditRecorder.record("masterdata.Clinic", clinic.getUid(), AuditAction.UPDATE);
        return mapper.toDto(clinic);
    }

    @Transactional(readOnly = true)
    public ClinicDto get(String uid) {
        Clinic clinic = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + uid));
        return mapper.toDto(clinic);
    }

    @Transactional(readOnly = true)
    public List<ClinicDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
