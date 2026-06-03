package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.DiagnosisTypeService;
import com.otapp.hmis.masterdata.application.dto.DiagnosisTypeDto;
import com.otapp.hmis.masterdata.application.dto.DiagnosisTypeRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the {@code DiagnosisType} catalog (build-spec §1.3, §3, CR-06).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3 gate map — exact legacy).
 *   <li>Reads require a valid JWT but carry no role gate.
 *   <li>Entity name is {@code DiagnosisType} (NOT "Diagnosis") — CR-06.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/diagnosis-types")
@RequiredArgsConstructor
public class DiagnosisTypeController {

    private final DiagnosisTypeService service;

    @GetMapping
    public List<DiagnosisTypeDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public DiagnosisTypeDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<DiagnosisTypeDto> create(@Valid @RequestBody DiagnosisTypeRequest request) {
        DiagnosisTypeDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/diagnosis-types/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public DiagnosisTypeDto update(@PathVariable("uid") String uid,
                                   @Valid @RequestBody DiagnosisTypeRequest request) {
        return service.update(uid, request);
    }
}
