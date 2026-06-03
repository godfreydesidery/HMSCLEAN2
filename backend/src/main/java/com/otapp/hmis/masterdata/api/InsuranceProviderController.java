package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.InsuranceProviderService;
import com.otapp.hmis.masterdata.application.dto.InsuranceProviderDto;
import com.otapp.hmis.masterdata.application.dto.InsuranceProviderRequest;
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
 * REST endpoints for the {@code InsuranceProvider} catalog (build-spec §1.4, §3).
 *
 * <ul>
 *   <li>Mutations ({@code POST}/{@code PUT}) gated {@code ADMIN-ACCESS} (build-spec §3 gate map,
 *       CR-15 DEVIATION-4 — legacy was ungated; tightened to ADMIN-ACCESS).</li>
 *   <li>Reads require a valid JWT but carry no role gate (legacy: masterdata GETs are
 *       role-ungated).</li>
 *   <li>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/insurance-providers")
@RequiredArgsConstructor
public class InsuranceProviderController {

    private final InsuranceProviderService service;

    @GetMapping
    public List<InsuranceProviderDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public InsuranceProviderDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<InsuranceProviderDto> create(
            @Valid @RequestBody InsuranceProviderRequest request) {
        InsuranceProviderDto created = service.create(request);
        URI location = URI.create(
                "/api/v1/masterdata/insurance-providers/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public InsuranceProviderDto update(@PathVariable("uid") String uid,
                                       @Valid @RequestBody InsuranceProviderRequest request) {
        return service.update(uid, request);
    }
}
