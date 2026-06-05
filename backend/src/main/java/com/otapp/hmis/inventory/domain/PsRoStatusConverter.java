package com.otapp.hmis.inventory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** JPA converter for {@link PsRoStatus} ↔ its DB string (hyphenated values, inc-08b). */
@Converter(autoApply = false)
public class PsRoStatusConverter implements AttributeConverter<PsRoStatus, String> {

    @Override
    public String convertToDatabaseColumn(PsRoStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public PsRoStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PsRoStatus.fromDbValue(dbData);
    }
}
