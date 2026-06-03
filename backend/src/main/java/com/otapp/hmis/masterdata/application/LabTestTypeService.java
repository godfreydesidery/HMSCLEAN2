package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.LabTestTypeDto;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeRangeDto;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeRangeRequest;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeRequest;
import com.otapp.hmis.masterdata.domain.LabTestType;
import com.otapp.hmis.masterdata.domain.LabTestTypeRange;
import com.otapp.hmis.masterdata.domain.LabTestTypeRangeRepository;
import com.otapp.hmis.masterdata.domain.LabTestTypeRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link LabTestType} and {@link LabTestTypeRange} catalog management
 * (build-spec §1.3, §5.1, AC-9.4).
 *
 * <p><b>Code-immutable-on-update (AC-9.4):</b> On update, the {@code code} field in the request
 * is IGNORED — {@link LabTestType#update} deliberately accepts no {@code code} parameter.
 * This reproduces the exact legacy quirk in {@code LabTestTypeServiceImpl.java:47-48} where the
 * update branch re-derives {@code code} from the already-persisted entity.
 *
 * <p>DO NOT implement {@code PUT /lab_test_types/update_by_code} — that endpoint does not
 * exist in legacy and the Angular path calling it is dead (AC-9.5 / 03-extract-clinical §Q2).
 */
@Service
@RequiredArgsConstructor
public class LabTestTypeService {

    private final LabTestTypeRepository repository;
    private final LabTestTypeRangeRepository rangeRepository;
    private final LabTestTypeMapper mapper;
    private final LabTestTypeRangeMapper rangeMapper;
    private final AuditRecorder auditRecorder;

    // -------------------------------------------------------------------------
    // LabTestType CRUD
    // -------------------------------------------------------------------------

    @Transactional
    public LabTestTypeDto create(LabTestTypeRequest request) {
        LabTestType ltt = new LabTestType(
                request.code(), request.name(), request.description(),
                request.price(), request.uom(), request.active());
        repository.save(ltt);
        auditRecorder.record("masterdata.LabTestType", ltt.getUid(), AuditAction.CREATE);
        return mapper.toDto(ltt);
    }

    /**
     * Updates a {@link LabTestType}. The {@code code} in the request is IGNORED —
     * {@code code} is immutable after creation (AC-9.4 / LabTestTypeServiceImpl.java:47-48).
     */
    @Transactional
    public LabTestTypeDto update(String uid, LabTestTypeRequest request) {
        LabTestType ltt = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("LabTestType not found: " + uid));
        // code intentionally omitted — immutable on update (AC-9.4)
        ltt.update(request.name(), request.description(),
                request.price(), request.uom(), request.active());
        auditRecorder.record("masterdata.LabTestType", ltt.getUid(), AuditAction.UPDATE);
        return mapper.toDto(ltt);
    }

    @Transactional(readOnly = true)
    public LabTestTypeDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("LabTestType not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<LabTestTypeDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }

    // -------------------------------------------------------------------------
    // LabTestTypeRange nested CRUD (exposed under /lab-test-types/uid/{uid}/ranges)
    // -------------------------------------------------------------------------

    @Transactional
    public LabTestTypeRangeDto createRange(String labTestTypeUid, LabTestTypeRangeRequest request) {
        LabTestType ltt = repository.findByUid(labTestTypeUid)
                .orElseThrow(() -> new NotFoundException("LabTestType not found: " + labTestTypeUid));
        LabTestTypeRange range = new LabTestTypeRange(request.name(), ltt);
        rangeRepository.save(range);
        auditRecorder.record("masterdata.LabTestTypeRange", range.getUid(), AuditAction.CREATE);
        return rangeMapper.toDto(range);
    }

    @Transactional
    public LabTestTypeRangeDto updateRange(String rangeUid, LabTestTypeRangeRequest request) {
        LabTestTypeRange range = rangeRepository.findByUid(rangeUid)
                .orElseThrow(() -> new NotFoundException("LabTestTypeRange not found: " + rangeUid));
        range.update(request.name());
        auditRecorder.record("masterdata.LabTestTypeRange", range.getUid(), AuditAction.UPDATE);
        return rangeMapper.toDto(range);
    }

    @Transactional
    public void deleteRange(String rangeUid) {
        LabTestTypeRange range = rangeRepository.findByUid(rangeUid)
                .orElseThrow(() -> new NotFoundException("LabTestTypeRange not found: " + rangeUid));
        auditRecorder.record("masterdata.LabTestTypeRange", range.getUid(), AuditAction.DELETE);
        rangeRepository.delete(range);
    }

    @Transactional(readOnly = true)
    public List<LabTestTypeRangeDto> listRanges(String labTestTypeUid) {
        LabTestType ltt = repository.findByUid(labTestTypeUid)
                .orElseThrow(() -> new NotFoundException("LabTestType not found: " + labTestTypeUid));
        return rangeMapper.toDtoList(rangeRepository.findAllByLabTestType(ltt));
    }

    @Transactional(readOnly = true)
    public LabTestTypeRangeDto getRange(String rangeUid) {
        return rangeMapper.toDto(rangeRepository.findByUid(rangeUid)
                .orElseThrow(() -> new NotFoundException("LabTestTypeRange not found: " + rangeUid)));
    }
}
