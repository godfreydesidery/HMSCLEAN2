package com.otapp.hmis.billing.domain;

import java.math.BigDecimal;

/**
 * Read-time aggregation projection for the EOD collections report (build-spec §5.1).
 *
 * <p>One row per {@code (item_name, payment_channel)} bucket, carrying the {@code SUM(amount)} over
 * the {@code collections} rows in the report window — the legacy cash-up figure
 * (CollectionRepository.java:21-59, CollectionReport.java:11-13). Spring Data binds the JPQL column
 * aliases to these getters. Because {@code payment_channel} is always {@code "Cash"} in legacy data,
 * the buckets collapse to a single channel in practice (multi-mode is [GATED:CR-08]).
 */
public interface CollectionSummary {

    String getItemName();

    String getPaymentChannel();

    BigDecimal getAmount();
}
