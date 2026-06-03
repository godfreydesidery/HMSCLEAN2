package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.LabTestTypeService;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeDto;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeRangeDto;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeRangeRequest;
import com.otapp.hmis.masterdata.application.dto.LabTestTypeRequest;
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
 * REST endpoints for the {@code LabTestType} catalog and its nested {@code LabTestTypeRange}
 * sub-resource (build-spec §1.3, §3, AC-9.4, AC-9.5).
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3 gate map).
 *   <li>Reads require a valid JWT but carry no role gate.
 *   <li>On PUT update, the {@code code} in the request body is IGNORED — code is immutable
 *       after creation (AC-9.4 / LabTestTypeServiceImpl.java:47-48).
 *   <li>DO NOT add {@code PUT /uid/{uid}/update_by_code} — that path is dead in legacy (AC-9.5).
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/lab-test-types")
@RequiredArgsConstructor
public class LabTestTypeController {

    private final LabTestTypeService service;

    // ------------------------------------------------------------------
    // LabTestType CRUD
    // ------------------------------------------------------------------

    @GetMapping
    public List<LabTestTypeDto> list() {
        return service.list();
    }

    @GetMapping("/uid/{uid}")
    public LabTestTypeDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<LabTestTypeDto> create(@Valid @RequestBody LabTestTypeRequest request) {
        LabTestTypeDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/lab-test-types/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public LabTestTypeDto update(@PathVariable("uid") String uid,
                                 @Valid @RequestBody LabTestTypeRequest request) {
        return service.update(uid, request);
    }

    // ------------------------------------------------------------------
    // LabTestTypeRange nested sub-resource (under /uid/{uid}/ranges)
    // ------------------------------------------------------------------

    @GetMapping("/uid/{uid}/ranges")
    public List<LabTestTypeRangeDto> listRanges(@PathVariable("uid") String uid) {
        return service.listRanges(uid);
    }

    @GetMapping("/uid/{uid}/ranges/{rangeUid}")
    public LabTestTypeRangeDto getRange(@PathVariable("uid") String uid,
                                        @PathVariable("rangeUid") String rangeUid) {
        return service.getRange(rangeUid);
    }

    @PostMapping("/uid/{uid}/ranges")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<LabTestTypeRangeDto> createRange(
            @PathVariable("uid") String uid,
            @Valid @RequestBody LabTestTypeRangeRequest request) {
        LabTestTypeRangeDto created = service.createRange(uid, request);
        URI location = URI.create(
                "/api/v1/masterdata/lab-test-types/uid/" + uid + "/ranges/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/uid/{uid}/ranges/{rangeUid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public LabTestTypeRangeDto updateRange(
            @PathVariable("uid") String uid,
            @PathVariable("rangeUid") String rangeUid,
            @Valid @RequestBody LabTestTypeRangeRequest request) {
        return service.updateRange(rangeUid, request);
    }

    @DeleteMapping("/uid/{uid}/ranges/{rangeUid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<Void> deleteRange(
            @PathVariable("uid") String uid,
            @PathVariable("rangeUid") String rangeUid) {
        service.deleteRange(rangeUid);
        return ResponseEntity.noContent().build();
    }
}
