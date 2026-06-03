package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.MdCurrencyService;
import com.otapp.hmis.masterdata.application.dto.MdCurrencyDto;
import com.otapp.hmis.masterdata.application.dto.MdCurrencyRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for system currency config (build-spec §1.5).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/masterdata/currencies} — list all; authenticated (no role gate).</li>
 *   <li>{@code POST /api/v1/masterdata/currencies} — create; gated {@code ADMIN-ACCESS}.</li>
 * </ul>
 *
 * <p>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).
 */
@RestController
@RequestMapping("/api/v1/masterdata/currencies")
@RequiredArgsConstructor
public class MdCurrencyController {

    private final MdCurrencyService service;

    @GetMapping
    public List<MdCurrencyDto> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<MdCurrencyDto> create(@Valid @RequestBody MdCurrencyRequest request) {
        MdCurrencyDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/currencies/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }
}
