package com.otapp.hmis.registration.domain;

/**
 * MOVED to {@link com.otapp.hmis.clinical.domain.Consultation} (ADR-0022, CR-21, inc-05 C2).
 *
 * <p>This stub class exists only so that the Maven compiler does not fail on any remaining
 * reference during the migration. It carries no {@code @Entity} annotation and maps no table.
 * All production code now references {@code com.otapp.hmis.clinical.domain.Consultation}.
 *
 * @deprecated Use {@code com.otapp.hmis.clinical.domain.Consultation} instead.
 *             This class will be removed once all tests are updated (ADR-0022 D6).
 */
@Deprecated(forRemoval = true)
public final class Consultation {
    private Consultation() {}
}
