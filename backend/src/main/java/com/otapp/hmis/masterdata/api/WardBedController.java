package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.WardBedService;
import com.otapp.hmis.masterdata.application.dto.WardBedDto;
import com.otapp.hmis.masterdata.application.dto.WardBedRequest;
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
 * REST endpoints for the {@code WardBed} catalog (build-spec §1.1, §2, §3).
 * Mutations gated {@code ADMIN-ACCESS}; reads require auth but no role gate.
 *
 * <p>Path is {@code /api/v1/masterdata/beds} (flat resource — build-spec §2 default).
 */
@RestController
@RequestMapping("/api/v1/masterdata/beds")
@RequiredArgsConstructor
public class WardBedController {

    private final WardBedService service;

    @GetMapping
    public List<WardBedDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public WardBedDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<WardBedDto> create(@Valid @RequestBody WardBedRequest request) {
        WardBedDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/beds/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public WardBedDto update(@PathVariable("uid") String uid,
                             @Valid @RequestBody WardBedRequest request) {
        return service.update(uid, request);
    }
}
