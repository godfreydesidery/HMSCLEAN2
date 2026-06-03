package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardBedDto;
import com.otapp.hmis.masterdata.application.dto.WardBedRequest;
import com.otapp.hmis.masterdata.domain.Ward;
import com.otapp.hmis.masterdata.domain.WardBed;
import com.otapp.hmis.masterdata.domain.WardBedRepository;
import com.otapp.hmis.masterdata.domain.WardRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link WardBed} catalog management (build-spec §1.1, §3).
 *
 * <p>The {@code ward} FK is set at construction time and is NOT changed on update
 * (legacy WardBed.java:49: {@code updatable=false} on the join column). Any {@code wardUid}
 * supplied in an update request body is silently ignored.
 */
@Service
@RequiredArgsConstructor
public class WardBedService {

    private final WardBedRepository repository;
    private final WardRepository wardRepository;
    private final WardBedMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public WardBedDto create(WardBedRequest request) {
        Ward ward = resolveWard(request.wardUid());
        WardBed wardBed = new WardBed(
                request.no(),
                request.status(),
                request.active(),
                ward);
        repository.save(wardBed);
        auditRecorder.record("masterdata.WardBed", wardBed.getUid(), AuditAction.CREATE);
        return mapper.toDto(wardBed);
    }

    /**
     * Update mutable fields only ({@code no}, {@code status}, {@code active}).
     * Ward reassignment is not supported — {@code wardUid} in the request is ignored (updatable=false).
     */
    @Transactional
    public WardBedDto update(String uid, WardBedRequest request) {
        WardBed wardBed = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardBed not found: " + uid));
        wardBed.update(request.no(), request.status(), request.active());
        auditRecorder.record("masterdata.WardBed", wardBed.getUid(), AuditAction.UPDATE);
        return mapper.toDto(wardBed);
    }

    @Transactional(readOnly = true)
    public WardBedDto get(String uid) {
        WardBed wardBed = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("WardBed not found: " + uid));
        return mapper.toDto(wardBed);
    }

    @Transactional(readOnly = true)
    public List<WardBedDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNoAsc());
    }

    // -------------------------------------------------------------------------

    private Ward resolveWard(String uid) {
        return wardRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Ward not found: " + uid));
    }
}
