package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.InsuranceProviderDto;
import com.otapp.hmis.masterdata.application.dto.InsuranceProviderRequest;
import com.otapp.hmis.masterdata.domain.InsuranceProvider;
import com.otapp.hmis.masterdata.domain.InsuranceProviderRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link InsuranceProvider} catalog management
 * (build-spec §1.4, §3 — gate: ADMIN-ACCESS).
 *
 * <p>Every mutation is audited via {@link AuditRecorder} within the same transaction
 * ({@code Propagation.MANDATORY} enforced by AuditRecorder — build-spec §5.5).
 * No {@code LocalDateTime.now()} called here (ArchUnit gate).
 */
@Service
@RequiredArgsConstructor
public class InsuranceProviderService {

    private final InsuranceProviderRepository repository;
    private final InsuranceProviderMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public InsuranceProviderDto create(InsuranceProviderRequest request) {
        InsuranceProvider provider = new InsuranceProvider(
                request.code(), request.name(), request.address(),
                request.telephone(), request.email(), request.fax(),
                request.website(), request.active());
        repository.save(provider);
        auditRecorder.record("masterdata.InsuranceProvider", provider.getUid(), AuditAction.CREATE);
        return mapper.toDto(provider);
    }

    @Transactional
    public InsuranceProviderDto update(String uid, InsuranceProviderRequest request) {
        InsuranceProvider provider = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("InsuranceProvider not found: " + uid));
        provider.update(request.code(), request.name(), request.address(),
                request.telephone(), request.email(), request.fax(),
                request.website(), request.active());
        auditRecorder.record("masterdata.InsuranceProvider", provider.getUid(), AuditAction.UPDATE);
        return mapper.toDto(provider);
    }

    @Transactional(readOnly = true)
    public InsuranceProviderDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("InsuranceProvider not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<InsuranceProviderDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
