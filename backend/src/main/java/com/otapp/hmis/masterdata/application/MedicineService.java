package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.MedicineDto;
import com.otapp.hmis.masterdata.application.dto.MedicineRequest;
import com.otapp.hmis.masterdata.domain.Medicine;
import com.otapp.hmis.masterdata.domain.MedicineRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Medicine} catalog management (build-spec §1.2, §3).
 *
 * <p>Every mutation is audited via {@link AuditRecorder} within the same transaction.
 * No {@code LocalDateTime.now()} / {@code Instant.now()} called here (ArchUnit gate).
 */
@Service
@RequiredArgsConstructor
public class MedicineService {

    private final MedicineRepository repository;
    private final MedicineMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public MedicineDto create(MedicineRequest request) {
        Medicine medicine = new Medicine(
                request.code(),
                request.name(),
                request.description(),
                request.type(),
                request.price(),
                request.uom(),
                request.category(),
                request.active());
        repository.save(medicine);
        auditRecorder.record("masterdata.Medicine", medicine.getUid(), AuditAction.CREATE);
        return mapper.toDto(medicine);
    }

    @Transactional
    public MedicineDto update(String uid, MedicineRequest request) {
        Medicine medicine = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Medicine not found: " + uid));
        medicine.update(
                request.code(),
                request.name(),
                request.description(),
                request.type(),
                request.price(),
                request.uom(),
                request.category(),
                request.active());
        auditRecorder.record("masterdata.Medicine", medicine.getUid(), AuditAction.UPDATE);
        return mapper.toDto(medicine);
    }

    @Transactional(readOnly = true)
    public MedicineDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Medicine not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<MedicineDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
