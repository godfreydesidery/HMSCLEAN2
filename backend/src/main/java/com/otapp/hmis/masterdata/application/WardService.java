package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardDto;
import com.otapp.hmis.masterdata.application.dto.WardRequest;
import com.otapp.hmis.masterdata.domain.Ward;
import com.otapp.hmis.masterdata.domain.WardCategory;
import com.otapp.hmis.masterdata.domain.WardCategoryRepository;
import com.otapp.hmis.masterdata.domain.WardRepository;
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
 * Application service for {@link Ward} catalog management (build-spec §1.1, §3).
 *
 * <p>FK uids ({@code wardCategoryUid}, {@code wardTypeUid}) are resolved to entities inside
 * the service; a {@link NotFoundException} is thrown for any unknown uid, returning 404
 * (build-spec §1.1 — "throw NotFoundException for missing uid/FK").
 */
@Service
@RequiredArgsConstructor
public class WardService {

    private final WardRepository repository;
    private final WardCategoryRepository wardCategoryRepository;
    private final WardTypeRepository wardTypeRepository;
    private final WardMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public WardDto create(WardRequest request) {
        WardCategory wardCategory = resolveCategory(request.wardCategoryUid());
        WardType wardType = resolveType(request.wardTypeUid());
        Ward ward = new Ward(
                request.code(),
                request.name(),
                request.noOfBeds(),
                request.active(),
                wardCategory,
                wardType);
        repository.save(ward);
        auditRecorder.record("masterdata.Ward", ward.getUid(), AuditAction.CREATE);
        return mapper.toDto(ward);
    }

    @Transactional
    public WardDto update(String uid, WardRequest request) {
        Ward ward = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Ward not found: " + uid));
        WardCategory wardCategory = resolveCategory(request.wardCategoryUid());
        WardType wardType = resolveType(request.wardTypeUid());
        ward.update(
                request.code(),
                request.name(),
                request.noOfBeds(),
                request.active(),
                wardCategory,
                wardType);
        auditRecorder.record("masterdata.Ward", ward.getUid(), AuditAction.UPDATE);
        return mapper.toDto(ward);
    }

    @Transactional(readOnly = true)
    public WardDto get(String uid) {
        Ward ward = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Ward not found: " + uid));
        return mapper.toDto(ward);
    }

    @Transactional(readOnly = true)
    public List<WardDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }

    // -------------------------------------------------------------------------
    // Private FK resolution helpers
    // -------------------------------------------------------------------------

    private WardCategory resolveCategory(String uid) {
        return wardCategoryRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardCategory not found: " + uid));
    }

    private WardType resolveType(String uid) {
        return wardTypeRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardType not found: " + uid));
    }
}
