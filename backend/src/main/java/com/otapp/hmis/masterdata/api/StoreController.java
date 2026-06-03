package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.StoreService;
import com.otapp.hmis.masterdata.application.dto.StoreDto;
import com.otapp.hmis.masterdata.application.dto.StoreRequest;
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
 * REST endpoints for the {@code Store} catalog (build-spec §1.1, §2, §3).
 * Catalog writes gated {@code ADMIN-ACCESS}. Stock-update endpoints (ITEM_STOCK-UPDATE gate)
 * are OUT OF SCOPE for P1 and will be added in the inventory increment.
 */
@RestController
@RequestMapping("/api/v1/masterdata/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService service;

    @GetMapping
    public List<StoreDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public StoreDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<StoreDto> create(@Valid @RequestBody StoreRequest request) {
        StoreDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/stores/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public StoreDto update(@PathVariable("uid") String uid,
                           @Valid @RequestBody StoreRequest request) {
        return service.update(uid, request);
    }
}
