package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;

/**
 * Thrown when {@code POST /api/v1/masterdata/company-profile} is attempted but a
 * company-profile row already exists (build-spec §1.5, CR-14).
 *
 * <p>The company-profile is a singleton: exactly one row is allowed. A second POST is
 * rejected with HTTP 409 Conflict, distinct type URI
 * {@code urn:hmis:error:company-profile-exists} so the Angular client can surface a
 * targeted "use PUT to update" message.
 *
 * <p>This is an improvement over legacy's silent {@code deleteAll()+keepOne} behaviour
 * (CompanyProfileServiceImpl.java:36-99) — CR-14 ratified.
 */
public class CompanyProfileExistsException extends HmisException {

    public CompanyProfileExistsException() {
        super(ErrorCode.COMPANY_PROFILE_EXISTS,
                "A company profile already exists. Use PUT /api/v1/masterdata/company-profile to update it.");
    }
}
