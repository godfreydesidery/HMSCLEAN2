package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Masterdata registry entry marking a Medicine as a consumable.
 *
 * <p>Maps the V49 {@code consumables} table (inc-07 07c, PatientServiceImpl.java:2259-2262).
 * The consumable chart service guard checks {@code ConsumableRepository.findByMedicineUid}
 * and rejects with "Medicine is not listed as consumable" if absent.
 *
 * <p>Mirrors {@link Dressing} exactly: loose ref to medicine_uid, no physical FK (ADR-0008 §1).
 *
 * <p>Legacy citation: domain/Consumable.java; ConsumableRepository.java;
 * PatientServiceImpl.java:2259-2262.
 * inc-07 07c.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "consumables")
public class Consumable extends AuditableEntity {

    /**
     * Loose ref to the Medicine uid (UNIQUE — one consumable entry per medicine).
     * (Consumable.java — medicineUid field)
     */
    @Column(name = "medicine_uid", length = 26, nullable = false, unique = true,
            updatable = false)
    private String medicineUid;

    /**
     * Create a new Consumable entry for a given Medicine uid.
     *
     * @param medicineUid loose uid of the Medicine
     * @return new Consumable (uid assigned on first persist)
     */
    public static Consumable forMedicine(String medicineUid) {
        Consumable c = new Consumable();
        c.medicineUid = medicineUid;
        return c;
    }
}
