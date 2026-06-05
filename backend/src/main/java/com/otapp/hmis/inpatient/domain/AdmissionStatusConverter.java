package com.otapp.hmis.inpatient.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter mapping {@link AdmissionStatus} to its EXACT legacy DB string (inc-07 07a).
 *
 * <p>Required because two legacy values are hyphenated ({@code IN-PROCESS}, {@code SIGNED-OUT})
 * and therefore cannot be Java enum constant names, so {@code @Enumerated(EnumType.STRING)} (which
 * persists the constant name) would write the wrong token and violate the V44
 * {@code ck_admissions_status} CHECK.
 *
 * <p>{@code autoApply = false}: applied explicitly via {@code @Convert} on the
 * {@link Admission#status} field, so it never leaks onto unrelated String enums.
 *
 * <p>Mirrors {@link com.otapp.hmis.clinical.domain.ConsultationStatusConverter} exactly.
 * Legacy citation: Admission.java:45 — free-text String status.
 */
@Converter(autoApply = false)
public class AdmissionStatusConverter implements AttributeConverter<AdmissionStatus, String> {

    @Override
    public String convertToDatabaseColumn(AdmissionStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public AdmissionStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AdmissionStatus.fromDbValue(dbData);
    }
}
