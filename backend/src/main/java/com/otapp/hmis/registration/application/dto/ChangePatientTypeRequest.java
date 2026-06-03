package com.otapp.hmis.registration.application.dto;

import com.otapp.hmis.registration.domain.PatientType;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/patients/uid/{uid}/patient-type}.
 *
 * <p>Carries the desired {@link PatientType} target.  Only {@code OUTPATIENT} and
 * {@code OUTSIDER} are legal targets — the service rejects {@code INPATIENT} and
 * {@code DECEASED} with {@code 422} (build-spec §8 C4, PatientResource.java:498-506).
 *
 * <p>The service enforces the following guards before applying the transition
 * (PatientResource.java:421-506, build-spec §2.3 state machine):
 * <ul>
 *   <li><b>OUTPATIENT → OUTSIDER</b>: blocked if the patient has any {@code PENDING}
 *       consultation (legacy "Can not change patient type, the patient has an active
 *       consultation.").  The status set is widened in inc-05 to include IN-PROCESS and
 *       TRANSFERRED (inc-05 widens these statuses).</li>
 *   <li><b>OUTSIDER → OUTPATIENT</b>: deferred — NonConsultation order-clearance check
 *       lands with inc-05/06 (REG-3); the flip proceeds unconditionally in inc-03.</li>
 *   <li><b>INPATIENT current</b>: always blocked —
 *       "This operation is not allowed for inpatients".</li>
 *   <li><b>DECEASED or other</b>: always blocked — "Patient type could not be changed."</li>
 * </ul>
 *
 * <p>Legacy citation: PatientResource.java:398-506 (change_type endpoint).
 */
public record ChangePatientTypeRequest(

        /**
         * The desired patient type.  Must be non-null; only {@code OUTPATIENT} or
         * {@code OUTSIDER} are accepted by the service (other values → 422).
         */
        @NotNull PatientType targetType
) {
}
