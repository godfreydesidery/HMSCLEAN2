package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardTypeDto;
import com.otapp.hmis.masterdata.application.dto.WardTypeRequest;
import com.otapp.hmis.masterdata.domain.WardType;
import com.otapp.hmis.masterdata.domain.WardTypeRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link WardType} catalog management (build-spec §1.1, §3).
 */
@Service
@RequiredArgsConstructor
public class WardTypeService {

    private final WardTypeRepository repository;
    private final WardTypeMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public WardTypeDto create(WardTypeRequest request) {
        WardType wardType = new WardType(
                request.code(),
                request.name(),
                request.description(),
                request.price(),
                request.active());
        repository.save(wardType);
        auditRecorder.record("masterdata.WardType", wardType.getUid(), AuditAction.CREATE);
        return mapper.toDto(wardType);
    }

    @Transactional
    public WardTypeDto update(String uid, WardTypeRequest request) {
        WardType wardType = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardType not found: " + uid));
        wardType.update(
                request.code(),
                request.name(),
                request.description(),
                request.price(),
                request.active());
        auditRecorder.record("masterdata.WardType", wardType.getUid(), AuditAction.UPDATE);
        return mapper.toDto(wardType);
    }

    @Transactional(readOnly = true)
    public WardTypeDto get(String uid) {
        WardType wardType = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardType not found: " + uid));
        return mapper.toDto(wardType);
    }

    @Transactional(readOnly = true)
    public List<WardTypeDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
