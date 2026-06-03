package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.SupplierItemPriceService;
import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceDto;
import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceListDto;
import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the {@code SupplierItemPrice} catalog (build-spec §1.2, §3).
 *
 * <ul>
 *   <li>Mutations ({@code POST}/{@code PUT}/{@code DELETE}) gated {@code SUPPLIER_PRICE_LIST-ALL}
 *       (build-spec §3 gate map — exact legacy code).
 *   <li>Reads require a valid JWT but carry no role gate.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/supplier-item-prices")
@RequiredArgsConstructor
public class SupplierItemPriceController {

    private final SupplierItemPriceService service;

    @GetMapping
    public List<SupplierItemPriceDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public SupplierItemPriceDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    /**
     * Returns the non-persistent price-list aggregation for a supplier
     * (legacy {@code SupplierItemPriceList} response wrapper).
     */
    @GetMapping("/by-supplier/{supplierUid}")
    public SupplierItemPriceListDto listBySupplier(@PathVariable("supplierUid") String supplierUid) {
        return service.listBySupplier(supplierUid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SUPPLIER_PRICE_LIST-ALL')")
    public ResponseEntity<SupplierItemPriceDto> create(@Valid @RequestBody SupplierItemPriceRequest request) {
        SupplierItemPriceDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/supplier-item-prices/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('SUPPLIER_PRICE_LIST-ALL')")
    public SupplierItemPriceDto update(@PathVariable("uid") String uid,
                                       @Valid @RequestBody SupplierItemPriceRequest request) {
        return service.update(uid, request);
    }

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('SUPPLIER_PRICE_LIST-ALL')")
    public ResponseEntity<Void> delete(@PathVariable("uid") String uid) {
        service.delete(uid);
        return ResponseEntity.noContent().build();
    }
}
