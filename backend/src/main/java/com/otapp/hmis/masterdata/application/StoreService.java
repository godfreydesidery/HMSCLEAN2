package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.StoreDto;
import com.otapp.hmis.masterdata.application.dto.StoreRequest;
import com.otapp.hmis.masterdata.domain.Store;
import com.otapp.hmis.masterdata.domain.StoreRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Store} catalog management (build-spec §1.1, §3).
 */
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository repository;
    private final StoreMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public StoreDto create(StoreRequest request) {
        Store store = new Store(
                request.code(),
                request.name(),
                request.description(),
                request.location(),
                request.category(),
                request.active());
        repository.save(store);
        auditRecorder.record("masterdata.Store", store.getUid(), AuditAction.CREATE);
        return mapper.toDto(store);
    }

    @Transactional
    public StoreDto update(String uid, StoreRequest request) {
        Store store = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Store not found: " + uid));
        store.update(
                request.code(),
                request.name(),
                request.description(),
                request.location(),
                request.category(),
                request.active());
        auditRecorder.record("masterdata.Store", store.getUid(), AuditAction.UPDATE);
        return mapper.toDto(store);
    }

    @Transactional(readOnly = true)
    public StoreDto get(String uid) {
        Store store = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Store not found: " + uid));
        return mapper.toDto(store);
    }

    @Transactional(readOnly = true)
    public List<StoreDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
