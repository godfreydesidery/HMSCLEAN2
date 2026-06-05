package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.AdministrationRouteDto;
import com.otapp.hmis.masterdata.application.dto.AdministrationRouteRequest;
import com.otapp.hmis.masterdata.domain.AdministrationRoute;
import com.otapp.hmis.masterdata.domain.AdministrationRouteRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the {@link AdministrationRoute} catalog (inc-07 07d, CR-07-MAR).
 * Mirrors {@link WardCategoryService}.
 */
@Service
@RequiredArgsConstructor
public class AdministrationRouteService {

    private static final String AUDIT_TYPE = "masterdata.AdministrationRoute";

    private final AdministrationRouteRepository repository;
    private final AdministrationRouteMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public AdministrationRouteDto create(AdministrationRouteRequest request) {
        AdministrationRoute route = new AdministrationRoute(
                request.code(),
                request.name(),
                request.description(),
                request.active());
        repository.save(route);
        auditRecorder.record(AUDIT_TYPE, route.getUid(), AuditAction.CREATE);
        return mapper.toDto(route);
    }

    @Transactional
    public AdministrationRouteDto update(String uid, AdministrationRouteRequest request) {
        AdministrationRoute route = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("AdministrationRoute not found: " + uid));
        route.update(
                request.code(),
                request.name(),
                request.description(),
                request.active());
        auditRecorder.record(AUDIT_TYPE, route.getUid(), AuditAction.UPDATE);
        return mapper.toDto(route);
    }

    @Transactional(readOnly = true)
    public AdministrationRouteDto get(String uid) {
        AdministrationRoute route = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("AdministrationRoute not found: " + uid));
        return mapper.toDto(route);
    }

    @Transactional(readOnly = true)
    public List<AdministrationRouteDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
