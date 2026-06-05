package com.otapp.hmis.shared.documentnumber;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * Sequence-backed implementation of {@link DocumentNumberService} (CR-09; ADR-0009 §5/§6).
 *
 * <p>Each call performs one atomic {@code nextval(seq_*_no)} (no {@code MAX(id)+1}, no
 * placeholder, no double-save) and formats the result with the EAT calendar date. The sequence is
 * selected from {@link DocumentType#sequenceName()} — an enum-declared <strong>allow-list</strong>,
 * never a string built from caller input — so the legacy prefix-collision risk (two types sharing
 * one prefix) cannot recur, and the interpolated identifier is not an injection surface.
 *
 * <p>Decoupled from any module's domain repository (inc-08 Q11): the {@code nextval} is issued via a
 * generic {@link EntityManager} native query, so {@code shared} owns the numbering mechanism without
 * depending on {@code billing} (or any other context). PCN behaviour is byte-identical to the prior
 * billing-owned implementation.
 *
 * <p>The {@code clock} is EAT by default and package-visibly overridable for deterministic tests,
 * mirroring {@link com.otapp.hmis.shared.audit.AuditRecorder} and
 * {@link com.otapp.hmis.shared.domain.BusinessDayService}.
 */
@Service
public class DocumentNumberServiceImpl implements DocumentNumberService {

    /** East Africa Time — the spec-mandated calendar zone for the date component. */
    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    @PersistenceContext
    private EntityManager entityManager;

    /** Defaults to EAT; tests may substitute a fixed clock to assert the date component. */
    private Clock clock = Clock.system(EAT);

    @Override
    public String next(DocumentType type) {
        // sequenceName() is an enum-declared allow-list value, never caller input — safe to interpolate.
        Object raw = entityManager
                .createNativeQuery("SELECT nextval('" + type.sequenceName() + "')")
                .getSingleResult();
        long seq = ((Number) raw).longValue();
        String date = LocalDate.now(clock).format(YYYYMMDD);
        // prefix + yyyyMMdd + "-" + seq — suffix unpadded (Formater.java:14-17)
        return type.prefix() + date + "-" + seq;
    }

    /** Test seam: substitute a fixed clock to make the date component deterministic. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
