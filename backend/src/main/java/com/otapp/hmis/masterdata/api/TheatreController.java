package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.TheatreService;
import com.otapp.hmis.masterdata.application.dto.TheatreDto;
import com.otapp.hmis.masterdata.application.dto.TheatreRequest;
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
 * REST endpoints for the {@code Theatre} catalog (build-spec §1.1, §2, §3).
 * Mutations gated {@code ADMIN-ACCESS}; reads require auth but no role gate.
 */
@RestController
@RequestMapping("/api/v1/masterdata/theatres")
@RequiredArgsConstructor
public class TheatreController {

    private final TheatreService service;

    @GetMapping
    public List<TheatreDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public TheatreDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<TheatreDto> create(@Valid @RequestBody TheatreRequest request) {
        TheatreDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/theatres/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public TheatreDto update(@PathVariable("uid") String uid,
                             @Valid @RequestBody TheatreRequest request) {
        return service.update(uid, request);
    }
}
