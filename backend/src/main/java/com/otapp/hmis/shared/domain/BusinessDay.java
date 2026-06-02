package com.otapp.hmis.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Operator-controlled business day (increment-00 spec, ADR-0009 §7). Every transactional entity in
 * later increments stamps {@code businessDayUid} from the open day. Accessors are Lombok-generated
 * (DIRECTIVE 1); the {@code open} factory and {@code close} mutator stay hand-authored.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "business_days")
public class BusinessDay extends AuditableEntity {

    public enum Status {
        OPEN,
        CLOSED
    }

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private Status status;

    public static BusinessDay open(LocalDate businessDate, Instant openedAt) {
        BusinessDay day = new BusinessDay();
        day.businessDate = businessDate;
        day.openedAt = openedAt;
        day.status = Status.OPEN;
        return day;
    }

    public void close(Instant closedAt) {
        this.closedAt = closedAt;
        this.status = Status.CLOSED;
    }
}
