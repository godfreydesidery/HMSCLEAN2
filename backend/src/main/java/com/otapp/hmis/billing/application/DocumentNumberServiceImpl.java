package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.domain.PatientCreditNoteRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Sequence-backed implementation of {@link DocumentNumberService} (build-spec §4.3, CR-09).
 *
 * <p>Each call performs one atomic {@code nextval(seq_*_no)} (no {@code MAX(id)+1}, no
 * {@code Math.random()} placeholder, no double-save) and formats the result with the EAT calendar
 * date. The {@code switch} maps each {@link DocumentType} onto its own per-type sequence, so the
 * legacy prefix-collision risk (two types sharing one prefix) cannot recur.
 *
 * <p>The {@code clock} is EAT by default and package-visibly overridable for deterministic tests,
 * mirroring {@link com.otapp.hmis.shared.audit.AuditRecorder}.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberServiceImpl implements DocumentNumberService {

    /** East Africa Time — the spec-mandated calendar zone for the date component. */
    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PatientCreditNoteRepository creditNoteRepository;

    /** Defaults to EAT; tests may substitute a fixed clock to assert the date component. */
    private Clock clock = Clock.system(EAT);

    @Override
    public String next(DocumentType type) {
        long seq = switch (type) {
            case PCN -> creditNoteRepository.nextPcnNo();
        };
        String date = LocalDate.now(clock).format(YYYYMMDD);
        // prefix + yyyyMMdd + "-" + seq — suffix unpadded (Formater.java:14-17)
        return type.prefix() + date + "-" + seq;
    }

    /** Test seam: substitute a fixed clock to make the date component deterministic. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
