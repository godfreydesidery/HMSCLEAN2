package com.otapp.hmis.inventory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** JPA converter for {@link SpToStatus} ↔ its DB string (inc-08b). */
@Converter(autoApply = false)
public class SpToStatusConverter implements AttributeConverter<SpToStatus, String> {

    @Override
    public String convertToDatabaseColumn(SpToStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public SpToStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SpToStatus.fromDbValue(dbData);
    }
}
