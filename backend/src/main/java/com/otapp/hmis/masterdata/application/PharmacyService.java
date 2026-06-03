package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.PharmacyDto;
import com.otapp.hmis.masterdata.application.dto.PharmacyRequest;
import com.otapp.hmis.masterdata.domain.Pharmacy;
import com.otapp.hmis.masterdata.domain.PharmacyRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Pharmacy} catalog management (build-spec §1.1, §3).
 */
@Service
@RequiredArgsConstructor
public class PharmacyService {

    private final PharmacyRepository repository;
    private final PharmacyMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public PharmacyDto create(PharmacyRequest request) {
        Pharmacy pharmacy = new Pharmacy(
                request.code(),
                request.name(),
                request.description(),
                request.location(),
                request.category(),
                request.active());
        repository.save(pharmacy);
        auditRecorder.record("masterdata.Pharmacy", pharmacy.getUid(), AuditAction.CREATE);
        return mapper.toDto(pharmacy);
    }

    @Transactional
    public PharmacyDto update(String uid, PharmacyRequest request) {
        Pharmacy pharmacy = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Pharmacy not found: " + uid));
        pharmacy.update(
                request.code(),
                request.name(),
                request.description(),
                request.location(),
                request.category(),
                request.active());
        auditRecorder.record("masterdata.Pharmacy", pharmacy.getUid(), AuditAction.UPDATE);
        return mapper.toDto(pharmacy);
    }

    @Transactional(readOnly = true)
    public PharmacyDto get(String uid) {
        Pharmacy pharmacy = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Pharmacy not found: " + uid));
        return mapper.toDto(pharmacy);
    }

    @Transactional(readOnly = true)
    public List<PharmacyDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
