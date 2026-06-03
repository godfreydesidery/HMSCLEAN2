package com.otapp.hmis.billing.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Collection}.
 *
 * <p>Supports the EOD collections report (P3 scope) and per-patient collection queries.
 * CollectionRepository.java in legacy was minimal; report aggregation was inline SQL.
 */
public interface CollectionRepository extends JpaRepository<Collection, Long> {

    /**
     * Find all collections in a date range (for EOD report).
     * Range: from.atStartOfDay()..to.atStartOfDay().plusDays(1) (inclusive of to).
     * PatientBillResource.java:415+ (read-time aggregation — P3 scope).
     */
    @Query("""
           SELECT c FROM Collection c
           WHERE c.createdAt >= :from
             AND c.createdAt < :to
           ORDER BY c.createdAt
           """)
    List<Collection> findByDateRange(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Find collections in a date range attributed to a specific cashier (by username).
     * PatientBillResource.java:532+ (per-cashier filter — P3 scope).
     */
    @Query("""
           SELECT c FROM Collection c
           WHERE c.createdAt >= :from
             AND c.createdAt < :to
             AND c.createdBy = :username
           ORDER BY c.createdAt
           """)
    List<Collection> findByDateRangeAndCashier(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("username") String username);

    // -------------------------------------------------------------------------
    // EOD collections report (P3) — read-time SUM aggregation over collections
    // (CollectionRepository.java:21-59; build-spec §5.1). Aggregates across ALL
    // users (NOT grouped by user); per-cashier adds a createdBy filter only.
    // -------------------------------------------------------------------------

    /**
     * General cash-up: {@code SUM(amount) GROUP BY (item_name, payment_channel)} over the window
     * {@code [from, to)} across all cashiers (legacy {@code getCollectionReportGeneral}).
     */
    @Query("""
           SELECT c.itemName AS itemName,
                  c.paymentChannel AS paymentChannel,
                  SUM(c.amount.amount) AS amount
           FROM Collection c
           WHERE c.createdAt >= :from
             AND c.createdAt < :to
           GROUP BY c.itemName, c.paymentChannel
           ORDER BY c.itemName, c.paymentChannel
           """)
    List<CollectionSummary> reportGeneral(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Per-cashier cash-up: same aggregation filtered to the user who recorded the collection
     * (legacy {@code getCollectionReportByCashier} keyed on the user — here by the denormalized
     * {@code created_by} username, avoiding a cross-module join to the iam {@code users} table).
     */
    @Query("""
           SELECT c.itemName AS itemName,
                  c.paymentChannel AS paymentChannel,
                  SUM(c.amount.amount) AS amount
           FROM Collection c
           WHERE c.createdAt >= :from
             AND c.createdAt < :to
             AND c.createdBy = :username
           GROUP BY c.itemName, c.paymentChannel
           ORDER BY c.itemName, c.paymentChannel
           """)
    List<CollectionSummary> reportByCashier(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("username") String username);
}
