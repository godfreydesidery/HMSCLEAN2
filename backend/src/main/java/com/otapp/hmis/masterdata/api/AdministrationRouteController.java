package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.AdministrationRouteService;
import com.otapp.hmis.masterdata.application.dto.AdministrationRouteDto;
import com.otapp.hmis.masterdata.application.dto.AdministrationRouteRequest;
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
 * REST endpoints for the {@code AdministrationRoute} catalog (inc-07 07d, CR-07-MAR).
 * Mutations gated {@code ADMIN-ACCESS}; reads require auth but no role gate. Mirrors
 * {@link WardCategoryController}.
 */
@RestController
@RequestMapping("/api/v1/masterdata/administration-routes")
@RequiredArgsConstructor
public class AdministrationRouteController {

    private final AdministrationRouteService service;

    @GetMapping
    public List<AdministrationRouteDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public AdministrationRouteDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<AdministrationRouteDto> create(
            @Valid @RequestBody AdministrationRouteRequest request) {
        AdministrationRouteDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/administration-routes/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public AdministrationRouteDto update(@PathVariable("uid") String uid,
                                         @Valid @RequestBody AdministrationRouteRequest request) {
        return service.update(uid, request);
    }
}
