package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ProcedureTypeDto;
import com.otapp.hmis.masterdata.application.dto.ProcedureTypeRequest;
import com.otapp.hmis.masterdata.domain.ProcedureType;
import com.otapp.hmis.masterdata.domain.ProcedureTypeRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link ProcedureType} catalog management (build-spec §1.3, §3).
 */
@Service
@RequiredArgsConstructor
public class ProcedureTypeService {

    private final ProcedureTypeRepository repository;
    private final ProcedureTypeMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public ProcedureTypeDto create(ProcedureTypeRequest request) {
        ProcedureType pt = new ProcedureType(
                request.code(), request.name(), request.description(),
                request.price(), request.uom(), request.active());
        repository.save(pt);
        auditRecorder.record("masterdata.ProcedureType", pt.getUid(), AuditAction.CREATE);
        return mapper.toDto(pt);
    }

    @Transactional
    public ProcedureTypeDto update(String uid, ProcedureTypeRequest request) {
        ProcedureType pt = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ProcedureType not found: " + uid));
        pt.update(request.code(), request.name(), request.description(),
                request.price(), request.uom(), request.active());
        auditRecorder.record("masterdata.ProcedureType", pt.getUid(), AuditAction.UPDATE);
        return mapper.toDto(pt);
    }

    @Transactional(readOnly = true)
    public ProcedureTypeDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ProcedureType not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<ProcedureTypeDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
