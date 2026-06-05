package com.otapp.hmis.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Create pharmacy→pharmacy RO request (inc-08b chunk 7). The two pharmacies must differ. */
public record PpRoRequest(@NotBlank String requestingPharmacyUid, @NotBlank String deliveringPharmacyUid) {
}
