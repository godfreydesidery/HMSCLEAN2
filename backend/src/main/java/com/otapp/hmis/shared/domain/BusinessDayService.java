package com.otapp.hmis.shared.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-day lifecycle (increment-00 spec, ADR-0009 §7).
 *
 * <p>{@link #currentUid()} returns the uid of the single OPEN day or throws
 * {@link NoDayOpenException} (→ HTTP 422, {@code urn:hmis:error:no-day-open}). Constructor injection
 * is Lombok-generated (DIRECTIVE 1); the {@code clock} is a fixed UTC default (no second
 * constructor needed — tests inject a fixed clock via {@link #withClock(Clock)}).
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

    @Transactional
    public BusinessDay open(LocalDate businessDate) {
        BusinessDay day = BusinessDay.open(businessDate, Instant.now(clock));
        return repository.save(day);
    }

    @Transactional
    public BusinessDay openToday() {
        return open(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }
}
