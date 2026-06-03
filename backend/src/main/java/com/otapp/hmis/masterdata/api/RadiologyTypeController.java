package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.RadiologyTypeService;
import com.otapp.hmis.masterdata.application.dto.RadiologyTypeDto;
import com.otapp.hmis.masterdata.application.dto.RadiologyTypeRequest;
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
 * REST endpoints for the {@code RadiologyType} catalog (build-spec §1.3, §3).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3 gate map).
 *   <li>Reads require a valid JWT but carry no role gate.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/radiology-types")
@RequiredArgsConstructor
public class RadiologyTypeController {

    private final RadiologyTypeService service;

    @GetMapping
    public List<RadiologyTypeDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public RadiologyTypeDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<RadiologyTypeDto> create(@Valid @RequestBody RadiologyTypeRequest request) {
        RadiologyTypeDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/radiology-types/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public RadiologyTypeDto update(@PathVariable("uid") String uid,
                                   @Valid @RequestBody RadiologyTypeRequest request) {
        return service.update(uid, request);
    }
}
