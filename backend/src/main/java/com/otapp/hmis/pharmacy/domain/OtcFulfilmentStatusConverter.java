package com.otapp.hmis.pharmacy.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter mapping {@link OtcFulfilmentStatus} to its EXACT legacy DB string
 * ({@code NOT-GIVEN}/{@code GIVEN}). Mirrors {@code PrescriptionStatusConverter}: the hyphenated
 * values are not valid Java identifiers, so {@code @Enumerated(STRING)} would violate the V40
 * {@code ck_psod_status} CHECK. Applied explicitly via {@code @Convert}.
 */
@Converter(autoApply = false)
public class OtcFulfilmentStatusConverter
        implements AttributeConverter<OtcFulfilmentStatus, String> {

    @Override
    public String convertToDatabaseColumn(OtcFulfilmentStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public OtcFulfilmentStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : OtcFulfilmentStatus.fromDbValue(dbData);
    }
}
