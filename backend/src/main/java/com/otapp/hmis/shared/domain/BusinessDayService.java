package com.otapp.hmis.shared.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-day lifecycle (increment-00 spec, ADR-0009 §7; extended in P5 for admin endpoints).
 *
 * <p>{@link #currentUid()} returns the uid of the single OPEN day or throws
 * {@link NoDayOpenException} (→ HTTP 422). Constructor injection is Lombok-generated
 * (DIRECTIVE 1); the {@code clock} defaults to UTC — tests inject a fixed clock via
 * {@link #withClock(Clock)}.
 *
 * <h2>P5 additions (build-spec §5)</h2>
 * <ul>
 *   <li>{@link #openToday()} — creates a new OPEN day for today's date; throws
 *       {@link BusinessDayAlreadyOpenException} (409) if one already exists.</li>
 *   <li>{@link #closeCurrentDay()} — closes the current OPEN day; throws
 *       {@link NoDayOpenException} (422) if none is open.</li>
 *   <li>{@link #currentDay()} — returns the OPEN day entity; throws
 *       {@link NoDayOpenException} (422) if none is open.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BusinessDayService {

    private final BusinessDayRepository repository;
    private Clock clock = Clock.systemUTC();

    /** Test seam: swap the clock for deterministic timestamps. Returns {@code this} for chaining. */
    BusinessDayService withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * @return the uid of the currently OPEN business day
     * @throws NoDayOpenException when no OPEN day exists
     */
    @Transactional(readOnly = true)
    public String currentUid() {
        return repository.findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status.OPEN)
                .map(BusinessDay::getUid)
                .orElseThrow(NoDayOpenException::new);
    }

    /**
     * Returns the currently OPEN business day entity.
     *
     * @throws NoDayOpenException when no OPEN day exists (→ HTTP 422)
     */
    @Transactional(readOnly = true)
    public BusinessDay currentDay() {
        return repository.findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status.OPEN)
                .orElseThrow(NoDayOpenException::new);
    }

    /**
     * Opens a new business day for today's UTC date.
     *
     * @throws BusinessDayAlreadyOpenException 409 if a day is already OPEN
     */
    @Transactional
    public BusinessDay openToday() {
        if (repository.findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status.OPEN).isPresent()) {
            throw new BusinessDayAlreadyOpenException();
        }
        BusinessDay day = BusinessDay.open(
                LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                Instant.now(clock));
        return repository.save(day);
    }

    /**
     * Closes the currently OPEN business day.
     *
     * @throws NoDayOpenException 422 if no day is currently open
     */
    @Transactional
    public BusinessDay closeCurrentDay() {
        BusinessDay day = repository.findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status.OPEN)
                .orElseThrow(NoDayOpenException::new);
        day.close(Instant.now(clock));
        return repository.save(day);
    }

    // ------------------------------------------------------------------
    // Internal helper kept for backward-compat (used by inc-00 tests)
    // ------------------------------------------------------------------

    @Transactional
    public BusinessDay open(LocalDate businessDate) {
        BusinessDay day = BusinessDay.open(businessDate, Instant.now(clock));
        return repository.save(day);
    }
}
