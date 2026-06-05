package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Create pharmacy→store RO request (inc-08b chunk 6). */
public record PsRoRequest(@NotBlank String pharmacyUid, @NotBlank String storeUid) {
}
