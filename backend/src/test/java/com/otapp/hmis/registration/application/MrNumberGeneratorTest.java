package com.otapp.hmis.registration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.otapp.hmis.registration.domain.PatientRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Unit golden-master for the MR-number format (build-spec §2.1, CR-02). The DB sequence is mocked;
 * the {@code @Transactional} on {@code next()} is inert on a raw (non-proxied) instance.
 */
class MrNumberGeneratorTest {

    private static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    @Test
    void formats_MRNO_year_seq_unpadded() {
        PatientRepository repo = mock(PatientRepository.class);
        when(repo.nextMrNo()).thenReturn(7L);
        MrNumberGenerator gen = new MrNumberGenerator(repo);
        gen.setClock(Clock.fixed(Instant.parse("2026-06-03T10:00:00Z"), EAT));

        // MRNO/{year}/{seq} — NO zero-padding (parity)
        assertThat(gen.next()).isEqualTo("MRNO/2026/7");
    }

    @Test
    void usesEatYear_acrossUtcMidnight() {
        // 2026-12-31T23:30Z == 2027-01-01T02:30 EAT -> EAT year is 2027 (deterministic pinning)
        PatientRepository repo = mock(PatientRepository.class);
        when(repo.nextMrNo()).thenReturn(1L);
        MrNumberGenerator gen = new MrNumberGenerator(repo);
        gen.setClock(Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), EAT));

        assertThat(gen.next()).isEqualTo("MRNO/2027/1");
    }

    @Test
    void largeSeq_notPadded() {
        PatientRepository repo = mock(PatientRepository.class);
        when(repo.nextMrNo()).thenReturn(123456L);
        MrNumberGenerator gen = new MrNumberGenerator(repo);
        gen.setClock(Clock.fixed(Instant.parse("2026-06-03T10:00:00Z"), EAT));

        assertThat(gen.next()).isEqualTo("MRNO/2026/123456");
    }
}
