package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.ItemService;
import com.otapp.hmis.masterdata.application.dto.ItemDto;
import com.otapp.hmis.masterdata.application.dto.ItemRequest;
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
 * REST endpoints for the {@code Item} catalog (build-spec §1.2, §3).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (CR-15 DEVIATION-1 — legacy gate commented out,
 *       referenced dead {@code PROCUREMENT-ACCESS}; gated to {@code ADMIN-ACCESS}).
 *   <li>Reads require a valid JWT but carry no role gate.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService service;

    @GetMapping
    public List<ItemDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public ItemDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<ItemDto> create(@Valid @RequestBody ItemRequest request) {
        ItemDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/items/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ItemDto update(@PathVariable("uid") String uid,
                          @Valid @RequestBody ItemRequest request) {
        return service.update(uid, request);
    }
}
