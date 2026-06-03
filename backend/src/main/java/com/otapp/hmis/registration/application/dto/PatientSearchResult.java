package com.otapp.hmis.registration.application.dto;

import java.util.List;

/**
 * Stable paginated search envelope (build-spec §6, CR-07). A small explicit record rather than a
 * raw Spring {@code Page} so the wire contract is stable and OpenAPI-friendly.
 *
 * @param content       the patients on this page
 * @param page          zero-based page index
 * @param size          page size
 * @param totalElements total matching patients
 * @param totalPages    total number of pages
 */
public record PatientSearchResult(
        List<PatientDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
