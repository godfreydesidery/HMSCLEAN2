package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown by {@link PriceLookupImpl#resolve} when no covered insurance row and no cash
 * fallback row exists for the given (planUid, kind, serviceUid, currency) combination
 * (build-spec §2.2 step 3).
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@link ErrorCode#SERVICE_PRICE_NOT_FOUND}
 * ({@code urn:hmis:error:service-price-not-found}) through
 * {@link com.otapp.hmis.shared.error.GlobalExceptionHandler}.
 *
 * <p>This class is declared in {@code masterdata.application} (not in {@code masterdata.lookup})
 * because it is an internal implementation detail of the lookup impl. Consuming modules catch
 * the HTTP 422 response; they do not catch this exception type directly.
 */
public class ServicePriceNotFoundException extends HmisException {

    public ServicePriceNotFoundException(String planUid,
                                         com.otapp.hmis.masterdata.lookup.ServiceKind kind,
                                         String serviceUid, String currency) {
        super(ErrorCode.SERVICE_PRICE_NOT_FOUND,
                "No service price found for kind=" + kind
                + ", serviceUid=" + serviceUid
                + ", planUid=" + planUid
                + ", currency=" + currency);
    }
}
