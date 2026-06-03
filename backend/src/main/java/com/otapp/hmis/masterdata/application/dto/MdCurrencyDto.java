package com.otapp.hmis.masterdata.application.dto;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.MdCurrency}
 * (build-spec §1.5). No {@code id} field (ADR-0014 §1).
 *
 * <p>The field is named {@code defaultCurrency} (not {@code isDefault}) to match the
 * entity property name and avoid the Lombok/MapStruct boolean-stripping ambiguity.
 * The JSON key serialises as {@code "defaultCurrency"} on the wire.
 */
public record MdCurrencyDto(
        String uid,
        String code,
        String name,
        boolean defaultCurrency
) {
}
