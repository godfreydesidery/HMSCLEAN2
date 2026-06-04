package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for adding a working or final diagnosis to a consultation (inc-05 C6).
 *
 * <p>Both {@code POST /consultations/uid/{uid}/working-diagnoses} and
 * {@code POST /consultations/uid/{uid}/final-diagnoses} share this request shape.
 *
 * <p>{@code diagnosisTypeUid} is MANDATORY (the legacy rejects a null diagnosis type with
 * a "Diagnosis type not found" message — PatientResource.java:1659).
 * {@code description} is optional free-text (TEXT column, nullable in legacy).
 *
 * @param diagnosisTypeUid  the ULID of the diagnosis type (mandatory loose ref to masterdata)
 * @param description       optional free-text description / notes for this diagnosis
 */
public record DiagnosisRequest(

        @NotBlank
        String diagnosisTypeUid,

        String description
) {
}
