package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.InsurancePlanDto;
import com.otapp.hmis.masterdata.application.dto.InsurancePlanRequest;
import com.otapp.hmis.masterdata.domain.InsurancePlan;
import com.otapp.hmis.masterdata.domain.InsurancePlanRepository;
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
 * Application service for {@link InsurancePlan} catalog management
 * (build-spec §1.4, §3 — gate: ADMIN-ACCESS).
 *
 * <p>Plans are nested under their provider in the URL scheme:
 * {@code GET/POST /masterdata/insurance-providers/uid/{uid}/plans}. The service accepts
 * the provider uid as a parameter for the create path; the repository lookup resolves it.
 *
 * <p>Every mutation is audited via {@link AuditRecorder} within the same transaction.
 */
@Service
@RequiredArgsConstructor
public class InsurancePlanService {

    private final InsurancePlanRepository planRepository;
    private final InsuranceProviderRepository providerRepository;
    private final InsurancePlanMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public InsurancePlanDto create(String providerUid, InsurancePlanRequest request) {
        InsuranceProvider provider = providerRepository.findByUid(providerUid)
                .orElseThrow(() -> new NotFoundException("InsuranceProvider not found: " + providerUid));
        InsurancePlan plan = new InsurancePlan(
                request.code(), request.name(), request.description(),
                request.active(), provider);
        planRepository.save(plan);
        auditRecorder.record("masterdata.InsurancePlan", plan.getUid(), AuditAction.CREATE);
        return mapper.toDto(plan);
    }

    @Transactional
    public InsurancePlanDto createWithProviderFromRequest(InsurancePlanRequest request) {
        InsuranceProvider provider = providerRepository.findByUid(request.insuranceProviderUid())
                .orElseThrow(() -> new NotFoundException(
                        "InsuranceProvider not found: " + request.insuranceProviderUid()));
        InsurancePlan plan = new InsurancePlan(
                request.code(), request.name(), request.description(),
                request.active(), provider);
        planRepository.save(plan);
        auditRecorder.record("masterdata.InsurancePlan", plan.getUid(), AuditAction.CREATE);
        return mapper.toDto(plan);
    }

    @Transactional
    public InsurancePlanDto update(String uid, InsurancePlanRequest request) {
        InsurancePlan plan = planRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("InsurancePlan not found: " + uid));
        InsuranceProvider provider = providerRepository.findByUid(request.insuranceProviderUid())
                .orElseThrow(() -> new NotFoundException(
                        "InsuranceProvider not found: " + request.insuranceProviderUid()));
        plan.update(request.code(), request.name(), request.description(),
                request.active(), provider);
        auditRecorder.record("masterdata.InsurancePlan", plan.getUid(), AuditAction.UPDATE);
        return mapper.toDto(plan);
    }

    @Transactional(readOnly = true)
    public InsurancePlanDto get(String uid) {
        return mapper.toDto(planRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("InsurancePlan not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<InsurancePlanDto> list() {
        return mapper.toDtoList(planRepository.findAllByOrderByNameAsc());
    }

    @Transactional(readOnly = true)
    public List<InsurancePlanDto> listByProvider(String providerUid) {
        InsuranceProvider provider = providerRepository.findByUid(providerUid)
                .orElseThrow(() -> new NotFoundException("InsuranceProvider not found: " + providerUid));
        return mapper.toDtoList(planRepository.findAllByInsuranceProviderOrderByNameAsc(provider));
    }
}
