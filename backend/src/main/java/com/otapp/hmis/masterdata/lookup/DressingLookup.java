package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module check: is a ProcedureType registered in the dressings masterdata?
 *
 * <p>Published as part of {@code masterdata :: lookup} named interface (Spring Modulith,
 * ADR-0008 §1). The implementation is package-private in {@code masterdata.application}.
 *
 * <p>The inpatient module uses this to enforce the dressing guard at chart-save time:
 * if the ProcedureType is not listed as a dressing, the service rejects with the verbatim
 * legacy message 'Procedure type is not listed as dressing' (422, AC-07B-DRS-02).
 *
 * <p>Legacy citation: Dressing.java:35-49; PatientServiceImpl.java:2094.
 * inc-07 07b / AC-07B-DRS-02.
 */
public interface DressingLookup {

    /**
     * Return {@code true} if the given ProcedureType uid is registered in the dressings catalog.
     *
     * @param procedureTypeUid the loose uid of the procedure type
     * @return {@code true} if the procedure type is listed as a dressing; {@code false} otherwise
     */
    boolean isDressing(String procedureTypeUid);
}
