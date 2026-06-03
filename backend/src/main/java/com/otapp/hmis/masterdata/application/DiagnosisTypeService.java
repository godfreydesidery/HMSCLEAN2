package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.DiagnosisTypeDto;
import com.otapp.hmis.masterdata.application.dto.DiagnosisTypeRequest;
import com.otapp.hmis.masterdata.domain.DiagnosisType;
import com.otapp.hmis.masterdata.domain.DiagnosisTypeRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link DiagnosisType} catalog management (build-spec §1.3, §3).
 *
 * <p>Entity name is {@code DiagnosisType} (NOT "Diagnosis") throughout (CR-06).
 */
@Service
@RequiredArgsConstructor
public class DiagnosisTypeService {

    private final DiagnosisTypeRepository repository;
    private final DiagnosisTypeMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public DiagnosisTypeDto create(DiagnosisTypeRequest request) {
        DiagnosisType dt = new DiagnosisType(
                request.code(), request.name(), request.description(), request.active());
        repository.save(dt);
        auditRecorder.record("masterdata.DiagnosisType", dt.getUid(), AuditAction.CREATE);
        return mapper.toDto(dt);
    }

    @Transactional
    public DiagnosisTypeDto update(String uid, DiagnosisTypeRequest request) {
        DiagnosisType dt = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("DiagnosisType not found: " + uid));
        dt.update(request.code(), request.name(), request.description(), request.active());
        auditRecorder.record("masterdata.DiagnosisType", dt.getUid(), AuditAction.UPDATE);
        return mapper.toDto(dt);
    }

    @Transactional(readOnly = true)
    public DiagnosisTypeDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("DiagnosisType not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<DiagnosisTypeDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
