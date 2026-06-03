package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.SupplierService;
import com.otapp.hmis.masterdata.application.dto.SupplierDto;
import com.otapp.hmis.masterdata.application.dto.SupplierRequest;
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
 * REST endpoints for the {@code Supplier} catalog (build-spec §1.2, §3).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (CR-15 DEVIATION-3 — legacy had
 *       {@code ADMIN-ACCESS} commented out; re-enabled per build-spec §3).
 *   <li>Reads require a valid JWT but carry no role gate.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService service;

    @GetMapping
    public List<SupplierDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public SupplierDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<SupplierDto> create(@Valid @RequestBody SupplierRequest request) {
        SupplierDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/suppliers/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public SupplierDto update(@PathVariable("uid") String uid,
                              @Valid @RequestBody SupplierRequest request) {
        return service.update(uid, request);
    }
}
