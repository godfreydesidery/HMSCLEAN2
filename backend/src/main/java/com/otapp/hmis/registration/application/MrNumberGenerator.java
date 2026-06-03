package com.otapp.hmis.registration.application;

import com.otapp.hmis.registration.domain.PatientRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates the patient MR number (build-spec §2.1, CR-02).
 *
 * <p>Format: {@code MRNO/{year}/{seq}} — byte-identical to legacy
 * (PatientServiceImpl.java:250 {@code "MRNO/"+Year.now().getValue()+"/"+patient.getId()})
 * EXCEPT the suffix is a dedicated DB sequence ({@code nextval('seq_mrno')}, V13) instead of the
 * surrogate PK. The decoupling is forced by ADR-0014 §1 (the {@code id} is hidden and never
 * exposed) and is RATIFIED (CR-02): the format string is unchanged; only the numeric provenance
 * differs. One atomic {@code nextval} — collision-free, replacing the legacy save-then-read race.
 * No zero-padding, no per-year reset (parity).
 *
 * <p>The {@code year} is pinned to <strong>EAT</strong> ({@code Africa/Dar_es_Salaam}) — the legacy
 * used bare {@code Year.now()} on an EAT-tz server; pinning makes it deterministic (ADR-0009 §7).
 * The {@code clock} is package-visibly overridable for tests, mirroring {@code AuditRecorder} /
 * {@code DocumentNumberServiceImpl}.
 */
@Component
@RequiredArgsConstructor
public class MrNumberGenerator {

    /** East Africa Time — the spec-mandated calendar zone for the year component. */
    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private final PatientRepository patientRepository;

    /** Defaults to EAT; tests may substitute a fixed clock to assert the year component. */
    private Clock clock = Clock.system(EAT);

    /**
     * Allocate the next MR number. {@code @Transactional} so the {@code nextval} runs in a writable
     * transaction — it joins the registration process's transaction in production (propagation
     * REQUIRED) and opens its own writable tx when called standalone (e.g. the concurrency test).
     *
     * @return e.g. {@code MRNO/2026/7}
     */
    @Transactional
    public String next() {
        int year = LocalDate.now(clock).getYear();
        long seq = patientRepository.nextMrNo();
        return "MRNO/" + year + "/" + seq;
    }

    /** Test seam: substitute a fixed clock to make the year component deterministic. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
