package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.ItemMedicineCoefficientService;
import com.otapp.hmis.masterdata.application.dto.ItemMedicineCoefficientDto;
import com.otapp.hmis.masterdata.application.dto.ItemMedicineCoefficientRequest;
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
 * REST endpoints for the {@code ItemMedicineCoefficient} catalog (build-spec §1.2, §3, §5.3).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (CR-15 DEVIATION-2 — legacy gate commented out,
 *       referenced dead {@code ROLE-CREATE}; gated to {@code ADMIN-ACCESS}).
 *   <li>Reads require a valid JWT but carry no role gate.
 *   <li>400 on zero quantities; 409 on duplicate (item, medicine) pair (build-spec §5.3).
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/item-medicine-coefficients")
@RequiredArgsConstructor
public class ItemMedicineCoefficientController {

    private final ItemMedicineCoefficientService service;

    @GetMapping
    public List<ItemMedicineCoefficientDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public ItemMedicineCoefficientDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @GetMapping("/by-medicine/{medicineUid}")
    public List<ItemMedicineCoefficientDto> listByMedicine(@PathVariable("medicineUid") String medicineUid) {
        return service.listByMedicine(medicineUid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<ItemMedicineCoefficientDto> create(
            @Valid @RequestBody ItemMedicineCoefficientRequest request) {
        ItemMedicineCoefficientDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/item-medicine-coefficients/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ItemMedicineCoefficientDto update(@PathVariable("uid") String uid,
                                             @Valid @RequestBody ItemMedicineCoefficientRequest request) {
        return service.update(uid, request);
    }
}
