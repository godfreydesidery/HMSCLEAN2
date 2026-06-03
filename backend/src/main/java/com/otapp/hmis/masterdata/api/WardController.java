package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.WardService;
import com.otapp.hmis.masterdata.application.dto.WardDto;
import com.otapp.hmis.masterdata.application.dto.WardRequest;
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
 * REST endpoints for the {@code Ward} catalog (build-spec §1.1, §2, §3).
 * Mutations gated {@code ADMIN-ACCESS}; reads require auth but no role gate.
 */
@RestController
@RequestMapping("/api/v1/masterdata/wards")
@RequiredArgsConstructor
public class WardController {

    private final WardService service;

    @GetMapping
    public List<WardDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public WardDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<WardDto> create(@Valid @RequestBody WardRequest request) {
        WardDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/wards/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public WardDto update(@PathVariable("uid") String uid,
                          @Valid @RequestBody WardRequest request) {
        return service.update(uid, request);
    }
}
