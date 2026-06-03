package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardCategoryDto;
import com.otapp.hmis.masterdata.application.dto.WardCategoryRequest;
import com.otapp.hmis.masterdata.domain.WardCategory;
import com.otapp.hmis.masterdata.domain.WardCategoryRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link WardCategory} catalog management (build-spec §1.1, §3).
 */
@Service
@RequiredArgsConstructor
public class WardCategoryService {

    private final WardCategoryRepository repository;
    private final WardCategoryMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public WardCategoryDto create(WardCategoryRequest request) {
        WardCategory wardCategory = new WardCategory(
                request.code(),
                request.name(),
                request.description(),
                request.active());
        repository.save(wardCategory);
        auditRecorder.record("masterdata.WardCategory", wardCategory.getUid(), AuditAction.CREATE);
        return mapper.toDto(wardCategory);
    }

    @Transactional
    public WardCategoryDto update(String uid, WardCategoryRequest request) {
        WardCategory wardCategory = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardCategory not found: " + uid));
        wardCategory.update(
                request.code(),
                request.name(),
                request.description(),
                request.active());
        auditRecorder.record("masterdata.WardCategory", wardCategory.getUid(), AuditAction.UPDATE);
        return mapper.toDto(wardCategory);
    }

    @Transactional(readOnly = true)
    public WardCategoryDto get(String uid) {
        WardCategory wardCategory = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardCategory not found: " + uid));
        return mapper.toDto(wardCategory);
    }

    @Transactional(readOnly = true)
    public List<WardCategoryDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
