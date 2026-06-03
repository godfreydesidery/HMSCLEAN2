package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ServicePrice} (build-spec §2.1, §2.2).
 *
 * <p>The two resolve queries mirror the JPQL equivalents of the COALESCE index expression
 * so the optimizer can use {@code uq_service_prices_plan_kind_svc_cur} /
 * {@code idx_service_prices_lookup} (build-spec §2.2).
 */
public interface ServicePriceRepository extends JpaRepository<ServicePrice, Long> {

    Optional<ServicePrice> findByUid(String uid);

    /**
     * Step 1 of PriceLookup.resolve — covered insurance hit.
     * Finds the first covered row for the given (planUid, kind, serviceUid, currency).
     * serviceUid may be null for REGISTRATION (plan-only keyed — CR-18).
     */
    @Query("""
            SELECT sp FROM ServicePrice sp
            WHERE sp.planUid = :planUid
              AND sp.kind    = :kind
              AND (:serviceUid IS NULL AND sp.serviceUid IS NULL
                   OR sp.serviceUid = :serviceUid)
              AND sp.currency = :currency
              AND sp.covered  = TRUE
            """)
    Optional<ServicePrice> findCoveredInsuranceRow(
            @Param("planUid") String planUid,
            @Param("kind") ServiceKind kind,
            @Param("serviceUid") String serviceUid,
            @Param("currency") String currency);

    /**
     * Step 2 of PriceLookup.resolve — cash fallback.
     * Finds the cash row (planUid IS NULL) for the given (kind, serviceUid, currency).
     */
    @Query("""
            SELECT sp FROM ServicePrice sp
            WHERE sp.planUid IS NULL
              AND sp.kind    = :kind
              AND (:serviceUid IS NULL AND sp.serviceUid IS NULL
                   OR sp.serviceUid = :serviceUid)
              AND sp.currency = :currency
            """)
    Optional<ServicePrice> findCashRow(
            @Param("kind") ServiceKind kind,
            @Param("serviceUid") String serviceUid,
            @Param("currency") String currency);

    /**
     * Pre-check for duplicate detection before insert (AC-5, build-spec §2.1).
     * Uses the same NULL-equality semantics as the COALESCE unique index:
     * plan_uid NULL = cash, service_uid NULL = REGISTRATION.
     *
     * <p>Returns {@code true} when a row with the same composite key already exists.
     * The comparison {@code count > 0} is done in Java because JPQL {@code COUNT()}
     * returns {@code Long}, not {@code boolean}.
     */
    @Query("""
            SELECT COUNT(sp) FROM ServicePrice sp
            WHERE (:planUid IS NULL AND sp.planUid IS NULL
                   OR sp.planUid = :planUid)
              AND sp.kind = :kind
              AND (:serviceUid IS NULL AND sp.serviceUid IS NULL
                   OR sp.serviceUid = :serviceUid)
              AND sp.currency = :currency
            """)
    long countByCompositeKey(
            @Param("planUid") String planUid,
            @Param("kind") ServiceKind kind,
            @Param("serviceUid") String serviceUid,
            @Param("currency") String currency);

    default boolean existsByCompositeKey(String planUid, ServiceKind kind,
                                         String serviceUid, String currency) {
        return countByCompositeKey(planUid, kind, serviceUid, currency) > 0;
    }

    /**
     * Fetches the single row matching the composite key (plan_uid, kind, service_uid, currency)
     * using the same NULL-equality semantics as the COALESCE unique index (AC-5).
     * Used by the upsert path to load the existing row for in-place UPDATE.
     */
    @Query("""
            SELECT sp FROM ServicePrice sp
            WHERE (:planUid IS NULL AND sp.planUid IS NULL
                   OR sp.planUid = :planUid)
              AND sp.kind = :kind
              AND (:serviceUid IS NULL AND sp.serviceUid IS NULL
                   OR sp.serviceUid = :serviceUid)
              AND sp.currency = :currency
            """)
    Optional<ServicePrice> findByCompositeKey(
            @Param("planUid") String planUid,
            @Param("kind") ServiceKind kind,
            @Param("serviceUid") String serviceUid,
            @Param("currency") String currency);

    List<ServicePrice> findAllByOrderByKindAscPlanUidAsc();
}
