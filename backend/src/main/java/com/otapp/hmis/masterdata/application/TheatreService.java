package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.TheatreDto;
import com.otapp.hmis.masterdata.application.dto.TheatreRequest;
import com.otapp.hmis.masterdata.domain.Theatre;
import com.otapp.hmis.masterdata.domain.TheatreRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Theatre} catalog management (build-spec §1.1, §3).
 */
@Service
@RequiredArgsConstructor
public class TheatreService {

    private final TheatreRepository repository;
    private final TheatreMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public TheatreDto create(TheatreRequest request) {
        Theatre theatre = new Theatre(
                request.code(),
                request.name(),
                request.description(),
                request.location(),
                request.active());
        repository.save(theatre);
        auditRecorder.record("masterdata.Theatre", theatre.getUid(), AuditAction.CREATE);
        return mapper.toDto(theatre);
    }

    @Transactional
    public TheatreDto update(String uid, TheatreRequest request) {
        Theatre theatre = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Theatre not found: " + uid));
        theatre.update(
                request.code(),
                request.name(),
                request.description(),
                request.location(),
                request.active());
        auditRecorder.record("masterdata.Theatre", theatre.getUid(), AuditAction.UPDATE);
        return mapper.toDto(theatre);
    }

    @Transactional(readOnly = true)
    public TheatreDto get(String uid) {
        Theatre theatre = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Theatre not found: " + uid));
        return mapper.toDto(theatre);
    }

    @Transactional(readOnly = true)
    public List<TheatreDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
