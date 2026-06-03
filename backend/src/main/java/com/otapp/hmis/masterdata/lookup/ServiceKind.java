package com.otapp.hmis.masterdata.lookup;

/**
 * The seven billable service categories in the unified pricing matrix (build-spec §2.1,
 * CR-04, CR-04/D15, CR-12).
 *
 * <p>Stored as VARCHAR(20) via {@code @Enumerated(EnumType.STRING)}.
 *
 * <p>Legacy citations — the seven live {@code *InsurancePlan} tables, each keyed by a
 * plan + a service-entity FK (04-extract-pricing-insurance §2):
 * <ul>
 *   <li>{@code REGISTRATION} — {@code registration_insurance_plans}: plan-only keyed,
 *       no service FK → {@code serviceUid} NULL in {@code service_prices} (CR-18).</li>
 *   <li>{@code CONSULTATION} — {@code consultation_insurance_plans}: keyed by Clinic FK
 *       → {@code serviceUid} = {@code Clinic.uid}.</li>
 *   <li>{@code LAB_TEST} — {@code lab_test_type_insurance_plans}: keyed by LabTestType FK.</li>
 *   <li>{@code MEDICINE} — {@code medicine_insurance_plans}: keyed by Medicine FK.</li>
 *   <li>{@code PROCEDURE} — {@code procedure_type_insurance_plans}: keyed by ProcedureType FK.</li>
 *   <li>{@code RADIOLOGY} — {@code radiology_type_insurance_plans}: keyed by RadiologyType FK.</li>
 *   <li>{@code WARD} — {@code ward_type_insurance_plans}: keyed by WardType FK (CR-12;
 *       per-stay NOT per-day — D15).</li>
 * </ul>
 *
 * <p>{@code WARD_DAY} does NOT exist — the legacy ward charge is per-stay (CR-04/D15).
 */
public enum ServiceKind {

    /** Registration fee; serviceUid is NULL (plan-only keyed — CR-18). */
    REGISTRATION,

    /** Outpatient consultation; serviceUid = Clinic.uid. */
    CONSULTATION,

    /** Laboratory test; serviceUid = LabTestType.uid. */
    LAB_TEST,

    /** Dispensed medicine; serviceUid = Medicine.uid. */
    MEDICINE,

    /** Clinical procedure; serviceUid = ProcedureType.uid. */
    PROCEDURE,

    /** Radiology examination; serviceUid = RadiologyType.uid. */
    RADIOLOGY,

    /**
     * Ward bed/room charge (per-stay, NOT per-day — CR-04/D15, CR-12).
     * serviceUid = WardType.uid (WardType-only resolution; per-ward override is
     * [GATED:CR-12] and is NOT implemented in this increment).
     */
    WARD
}
