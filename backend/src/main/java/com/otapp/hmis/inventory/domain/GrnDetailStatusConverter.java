package com.otapp.hmis.inventory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** JPA converter for {@link GrnDetailStatus} ↔ its DB string (inc-08b). Mirrors the prescription converter. */
@Converter(autoApply = false)
public class GrnDetailStatusConverter implements AttributeConverter<GrnDetailStatus, String> {

    @Override
    public String convertToDatabaseColumn(GrnDetailStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public GrnDetailStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : GrnDetailStatus.fromDbValue(dbData);
    }
}
