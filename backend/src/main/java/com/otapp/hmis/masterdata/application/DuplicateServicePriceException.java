package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when a {@code POST /api/v1/masterdata/service-prices} request attempts to insert a
 * row whose (plan_uid, kind, service_uid, currency) composite key already exists in
 * {@code service_prices} (build-spec §2.1, AC-5).
 *
 * <p>Maps to HTTP 409 Conflict via {@link ErrorCode#DUPLICATE_SERVICE_PRICE}
 * ({@code urn:hmis:error:duplicate-service-price}).
 *
 * <p>The duplicate detection handles NULL plan_uid (cash rows) and NULL service_uid
 * (REGISTRATION rows) correctly because the database enforces uniqueness via the
 * COALESCE expression index {@code uq_service_prices_plan_kind_svc_cur}; the service
 * layer pre-checks via a JPQL existence query using the same COALESCE semantics so the
 * 409 is returned before hitting the constraint (avoids a DB exception that would roll
 * back the transaction before we could map it gracefully).
 */
public class DuplicateServicePriceException extends HmisException {

    public DuplicateServicePriceException(String planUid,
                                          com.otapp.hmis.masterdata.lookup.ServiceKind kind,
                                          String serviceUid, String currency) {
        super(ErrorCode.DUPLICATE_SERVICE_PRICE,
                "A service price already exists for kind=" + kind
                + ", serviceUid=" + serviceUid
                + ", planUid=" + planUid
                + ", currency=" + currency);
    }
}
