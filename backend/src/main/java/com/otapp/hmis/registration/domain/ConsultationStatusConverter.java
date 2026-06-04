package com.otapp.hmis.registration.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter mapping {@link ConsultationStatus} to its EXACT legacy DB string
 * (inc-05 build-spec §1). Required because two legacy values are hyphenated
 * ({@code IN-PROCESS}, {@code SIGNED-OUT}) and therefore cannot be Java enum constant names,
 * so {@code @Enumerated(EnumType.STRING)} (which persists the constant name) would write the
 * wrong token and violate the V20 {@code ck_consultations_status} CHECK.
 *
 * <p>{@code autoApply = false}: applied explicitly via {@code @Convert} on the
 * {@link Consultation#status} field, so it never leaks onto unrelated String enums.
 */
@Converter(autoApply = false)
public class ConsultationStatusConverter implements AttributeConverter<ConsultationStatus, String> {

    @Override
    public String convertToDatabaseColumn(ConsultationStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public ConsultationStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ConsultationStatus.fromDbValue(dbData);
    }
}
