package com.otapp.hmis.clinical.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter mapping {@link NonConsultationStatus} to its EXACT legacy DB string
 * (inc-05 C4 build-spec §1). Required because both legacy values are hyphenated
 * ({@code IN-PROCESS}, {@code SIGNED-OUT}) and therefore cannot be Java enum constant names,
 * so {@code @Enumerated(EnumType.STRING)} would write the wrong token and violate the
 * V21 {@code ck_non_consultations_status} CHECK.
 *
 * <p>{@code autoApply = false}: applied explicitly via {@code @Convert} on the
 * {@link NonConsultation#status} field, so it never leaks onto unrelated String enums.
 * Mirrors {@link ConsultationStatusConverter} exactly.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Status values: PatientServiceImpl.java:791 (IN-PROCESS), PatientResource.java:350 (SIGNED-OUT)</li>
 * </ul>
 */
@Converter(autoApply = false)
public class NonConsultationStatusConverter
        implements AttributeConverter<NonConsultationStatus, String> {

    @Override
    public String convertToDatabaseColumn(NonConsultationStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public NonConsultationStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : NonConsultationStatus.fromDbValue(dbData);
    }
}
