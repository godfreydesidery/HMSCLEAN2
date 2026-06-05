package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Masterdata registry entry marking a ProcedureType as a dressing procedure.
 *
 * <p>Maps the V48 {@code dressings} table (inc-07 07b, AC-07B-DRS-02).
 * The dressing service guard checks {@code DressingRepository.findByProcedureTypeUid}
 * and rejects with 'Procedure type is not listed as dressing' if absent
 * (Dressing.java:35-49; PatientServiceImpl.java:2094).
 *
 * <p>Loose ref to procedure_type_uid (no physical FK — ADR-0008 §1, masterdata module
 * boundary).
 *
 * <p>Legacy citation: Dressing.java:35-49.
 * inc-07 07b / AC-07B-DRS-02.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "dressings")
public class Dressing extends AuditableEntity {

    /**
     * Loose ref to the ProcedureType uid (UNIQUE — one dressing entry per procedure type).
     * (Dressing.java:40-43)
     */
    @Column(name = "procedure_type_uid", length = 26, nullable = false, unique = true,
            updatable = false)
    private String procedureTypeUid;

    /**
     * Create a new Dressing entry for a given ProcedureType uid.
     *
     * @param procedureTypeUid loose uid of the ProcedureType
     * @return new Dressing (uid assigned on first persist)
     */
    public static Dressing forProcedureType(String procedureTypeUid) {
        Dressing d = new Dressing();
        d.procedureTypeUid = procedureTypeUid;
        return d;
    }
}
