package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.RadiologyTypeDto;
import com.otapp.hmis.masterdata.application.dto.RadiologyTypeRequest;
import com.otapp.hmis.masterdata.domain.RadiologyType;
import com.otapp.hmis.masterdata.domain.RadiologyTypeRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link RadiologyType} catalog management (build-spec §1.3, §3).
 */
@Service
@RequiredArgsConstructor
public class RadiologyTypeService {

    private final RadiologyTypeRepository repository;
    private final RadiologyTypeMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public RadiologyTypeDto create(RadiologyTypeRequest request) {
        RadiologyType rt = new RadiologyType(
                request.code(), request.name(), request.description(),
                request.price(), request.uom(), request.active());
        repository.save(rt);
        auditRecorder.record("masterdata.RadiologyType", rt.getUid(), AuditAction.CREATE);
        return mapper.toDto(rt);
    }

    @Transactional
    public RadiologyTypeDto update(String uid, RadiologyTypeRequest request) {
        RadiologyType rt = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("RadiologyType not found: " + uid));
        rt.update(request.code(), request.name(), request.description(),
                request.price(), request.uom(), request.active());
        auditRecorder.record("masterdata.RadiologyType", rt.getUid(), AuditAction.UPDATE);
        return mapper.toDto(rt);
    }

    @Transactional(readOnly = true)
    public RadiologyTypeDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("RadiologyType not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<RadiologyTypeDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
