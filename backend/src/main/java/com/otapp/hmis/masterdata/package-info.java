/**
 * Master / reference data (ADR-0006, ADR-0008). Owns {@code CompanyProfile}, the increment-00
 * vertical slice. May depend on {@code iam} (clinic-clinician affiliation checks in later
 * increments); {@code iam} never depends back on {@code masterdata}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "iam")
package com.otapp.hmis.masterdata;
