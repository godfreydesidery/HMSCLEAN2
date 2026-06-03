package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.PharmacyService;
import com.otapp.hmis.masterdata.application.dto.PharmacyDto;
import com.otapp.hmis.masterdata.application.dto.PharmacyRequest;
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
 * REST endpoints for the {@code Pharmacy} catalog (build-spec §1.1, §2, §3).
 * Catalog writes gated {@code ADMIN-ACCESS}. Stock-update endpoints (MEDICINE_STOCK-UPDATE gate)
 * are OUT OF SCOPE for P1 and will be added in the pharmacy/inventory increment.
 */
@RestController
@RequestMapping("/api/v1/masterdata/pharmacies")
@RequiredArgsConstructor
public class PharmacyController {

    private final PharmacyService service;

    @GetMapping
    public List<PharmacyDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public PharmacyDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<PharmacyDto> create(@Valid @RequestBody PharmacyRequest request) {
        PharmacyDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/pharmacies/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public PharmacyDto update(@PathVariable("uid") String uid,
                              @Valid @RequestBody PharmacyRequest request) {
        return service.update(uid, request);
    }
}
