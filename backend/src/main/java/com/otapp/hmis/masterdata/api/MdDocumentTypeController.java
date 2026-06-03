package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.MdDocumentTypeService;
import com.otapp.hmis.masterdata.application.dto.MdDocumentTypeDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the document-type prefix registry (build-spec §1.5, CR-09, CR-10).
 *
 * <ul>
 *   <li>{@code GET /api/v1/masterdata/document-types} — list all seeded entries; authenticated
 *       (no role gate — reads are role-ungated per legacy masterdata convention, build-spec §3).
 *   </li>
 * </ul>
 *
 * <p>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).
 */
@RestController
@RequestMapping("/api/v1/masterdata/document-types")
@RequiredArgsConstructor
public class MdDocumentTypeController {

    private final MdDocumentTypeService service;

    @GetMapping
    public List<MdDocumentTypeDto> list() {
        return service.list();
    }
}
