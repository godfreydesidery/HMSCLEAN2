package com.otapp.hmis.registration.application.dto;

import java.time.Instant;

/**
 * The patient's last-visit timestamp (build-spec §6, CR-08). {@code null} only if the patient has
 * no visits (registration always creates a FIRST visit, so this is effectively always present).
 *
 * @param lastVisitAt the most-recent Visit's creation instant
 */
public record LastVisitDto(Instant lastVisitAt) {
}
