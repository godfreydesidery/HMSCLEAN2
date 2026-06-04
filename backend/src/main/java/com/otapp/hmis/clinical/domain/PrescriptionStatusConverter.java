package com.otapp.hmis.clinical.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter mapping {@link PrescriptionStatus} to its EXACT legacy DB string.
 *
 * <p>Required because both legacy values are hyphenated ({@code NOT-GIVEN}, {@code GIVEN}),
 * making them not valid Java identifier names. {@code @Enumerated(EnumType.STRING)} would
 * persist {@code NOT_GIVEN} and {@code GIVEN} — violating the V27
 * {@code ck_prescriptions_status} CHECK constraint.
 *
 * <p>{@code autoApply = false}: applied explicitly via {@code @Convert} on the
 * {@link Prescription#status} field.
 *
 * <p>Legacy citation: Prescription.java:50; V27 ck_prescriptions_status CHECK
 * ({@code 'NOT-GIVEN', 'GIVEN'}).
 */
@Converter(autoApply = false)
public class PrescriptionStatusConverter
        implements AttributeConverter<PrescriptionStatus, String> {

    @Override
    public String convertToDatabaseColumn(PrescriptionStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public PrescriptionStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PrescriptionStatus.fromDbValue(dbData);
    }
}
