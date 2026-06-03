package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.CollectionReportRow;
import com.otapp.hmis.billing.domain.CollectionRepository;
import com.otapp.hmis.billing.domain.CollectionSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EOD collections (cash-up) report — read-time aggregation over {@code collections}
 * (build-spec §5.1; legacy {@code getCollectionReportGeneral}/{@code getCollectionReportByCashier},
 * Ext 3 §4). NOT a persisted snapshot (CashierShift is [GATED:CR-04 — DEFERRED]); every call
 * re-aggregates {@code SUM(amount) GROUP BY (item_name, payment_channel)}.
 *
 * <p>The {@code [from, to]} day range is rendered as {@code [from 00:00, (to+1) 00:00)} on the
 * hospital's EAT calendar (so an operator's "today" means the EAT business day; consistent with the
 * EAT date used elsewhere — CR-09). The legacy used the JVM's forced-UTC start-of-day; using EAT
 * here is the operationally-correct window for a Tanzanian cashier and does not affect the
 * (BigDecimal, exact) sums.
 */
@Service
@RequiredArgsConstructor
public class CollectionReportService {

    /** Hospital calendar zone for the day-range boundary (build-spec §4.3 EAT, CR-09). */
    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private final CollectionRepository collectionRepository;

    /**
     * Aggregate collections for the inclusive day range {@code [from, to]}. When {@code cashier} is
     * non-blank the report is filtered to that user (per-cashier cash-up); otherwise it sums across
     * all users (general cash-up — NOT grouped by user).
     *
     * @param from    first business day (inclusive)
     * @param to      last business day (inclusive)
     * @param cashier optional username to filter on (null/blank ⇒ general report)
     * @return one row per {@code (itemName, paymentChannel)} bucket with the summed amount
     */
    @Transactional(readOnly = true)
    public List<CollectionReportRow> collectionsReport(LocalDate from, LocalDate to, String cashier) {
        Instant fromInstant = from.atStartOfDay(EAT).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(EAT).toInstant();

        List<CollectionSummary> rows = (cashier == null || cashier.isBlank())
                ? collectionRepository.reportGeneral(fromInstant, toExclusive)
                : collectionRepository.reportByCashier(fromInstant, toExclusive, cashier);

        return rows.stream()
                .map(r -> new CollectionReportRow(
                        r.getItemName(),
                        r.getPaymentChannel(),
                        scale(r.getAmount())))
                .toList();
    }

    /** Normalise the SUM to NUMERIC(19,2) HALF_UP (null sum ⇒ zero). */
    private static BigDecimal scale(BigDecimal amount) {
        return (amount != null ? amount : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
