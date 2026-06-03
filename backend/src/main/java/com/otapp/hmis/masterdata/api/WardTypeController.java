package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.WardTypeService;
import com.otapp.hmis.masterdata.application.dto.WardTypeDto;
import com.otapp.hmis.masterdata.application.dto.WardTypeRequest;
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
 * REST endpoints for the {@code WardType} catalog (build-spec §1.1, §2, §3).
 * Mutations gated {@code ADMIN-ACCESS}; reads require auth but no role gate.
 */
@RestController
@RequestMapping("/api/v1/masterdata/ward-types")
@RequiredArgsConstructor
public class WardTypeController {

    private final WardTypeService service;

    @GetMapping
    public List<WardTypeDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public WardTypeDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<WardTypeDto> create(@Valid @RequestBody WardTypeRequest request) {
        WardTypeDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/ward-types/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public WardTypeDto update(@PathVariable("uid") String uid,
                              @Valid @RequestBody WardTypeRequest request) {
        return service.update(uid, request);
    }
}
