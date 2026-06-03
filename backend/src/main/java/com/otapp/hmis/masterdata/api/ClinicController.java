package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.ClinicService;
import com.otapp.hmis.masterdata.application.dto.ClinicDto;
import com.otapp.hmis.masterdata.application.dto.ClinicRequest;
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
 * REST endpoints for the {@code Clinic} catalog (build-spec §1.1, §2, §3).
 *
 * <ul>
 *   <li>Mutations ({@code POST}/{@code PUT}) gated {@code ADMIN-ACCESS} (build-spec §3 gate map).
 *   <li>Reads require a valid JWT but carry no role gate (legacy: masterdata GETs are role-ungated).
 *   <li>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).
 *   <li>No class-level {@code @PreAuthorize} (build-spec §2).
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/clinics")
@RequiredArgsConstructor
public class ClinicController {

    private final ClinicService service;

    @GetMapping
    public List<ClinicDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public ClinicDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<ClinicDto> create(@Valid @RequestBody ClinicRequest request) {
        ClinicDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/clinics/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ClinicDto update(@PathVariable("uid") String uid,
                            @Valid @RequestBody ClinicRequest request) {
        return service.update(uid, request);
    }
}
