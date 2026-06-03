package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.ItemSupplierService;
import com.otapp.hmis.masterdata.application.dto.ItemSupplierDto;
import com.otapp.hmis.masterdata.application.dto.ItemSupplierRequest;
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
 * REST endpoints for the {@code ItemSupplier} catalog (build-spec §1.2, §3).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3 — item/supplier catalog writes).
 *   <li>Reads require a valid JWT but carry no role gate.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/item-suppliers")
@RequiredArgsConstructor
public class ItemSupplierController {

    private final ItemSupplierService service;

    @GetMapping
    public List<ItemSupplierDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public ItemSupplierDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @GetMapping("/by-item/{itemUid}")
    public List<ItemSupplierDto> listByItem(@PathVariable("itemUid") String itemUid) {
        return service.listByItem(itemUid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<ItemSupplierDto> create(@Valid @RequestBody ItemSupplierRequest request) {
        ItemSupplierDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/item-suppliers/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ItemSupplierDto update(@PathVariable("uid") String uid,
                                  @Valid @RequestBody ItemSupplierRequest request) {
        return service.update(uid, request);
    }
}
