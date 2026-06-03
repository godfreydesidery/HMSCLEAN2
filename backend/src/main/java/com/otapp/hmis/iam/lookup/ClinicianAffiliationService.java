package com.otapp.hmis.iam.lookup;

import java.util.List;

/**
 * Cross-module API for clinic–clinician affiliation management (CR-08, build-spec §5.2).
 *
 * <p>Implementation is package-private in {@code com.otapp.hmis.iam.application}.
 * Consumers (masterdata module) depend on this interface ONLY — never on iam domain entities.
 *
 * <p>Affiliation ownership stays in {@code iam} as an {@code @ElementCollection} of opaque
 * clinic-uid strings on the {@code Clinician} entity (no FK to masterdata.Clinic, no reverse
 * iam→masterdata edge). The masterdata module orchestrates by calling this interface after
 * its own authorization checks (CLINICIAN role gate, clinic existence check).
 *
 * <p>All operations are addressed by USER uid (the public ULID on the {@code User} entity),
 * not by clinician uid, so callers do not need to know the internal iam domain shape.
 */
public interface ClinicianAffiliationService {

    /**
     * Idempotently affiliates the clinician identified by {@code userUid} with the given
     * {@code clinicUid}. If the user has no Clinician personnel extension, throws
     * {@link com.otapp.hmis.shared.error.NotFoundException} (the caller must ensure the user
     * holds the CLINICIAN role and has an extension before calling this).
     *
     * @param userUid   the public ULID of the User whose Clinician extension to update
     * @param clinicUid the opaque masterdata Clinic.uid string (no FK validation here)
     * @throws com.otapp.hmis.shared.error.NotFoundException if no Clinician extension exists for userUid
     */
    void affiliateClinic(String userUid, String clinicUid);

    /**
     * Idempotently removes the affiliation between the clinician identified by {@code userUid}
     * and the given {@code clinicUid}. Safe to call when the affiliation does not exist (no-op).
     * If no Clinician extension exists for the user, silently does nothing.
     *
     * @param userUid   the public ULID of the User
     * @param clinicUid the opaque masterdata Clinic.uid string
     */
    void removeClinicAffiliation(String userUid, String clinicUid);

    /**
     * Returns the list of clinic UIDs affiliated with the clinician identified by {@code userUid}.
     * Returns an empty list if the user has no Clinician extension or no affiliations.
     *
     * @param userUid the public ULID of the User
     * @return list of clinic uid strings (unordered)
     */
    List<String> clinicUidsOf(String userUid);

    /**
     * Returns the USER UIDs of all clinicians affiliated with the given {@code clinicUid}.
     * Used by the masterdata module to resolve "which clinician users are at this clinic?"
     * without exposing iam domain entities.
     *
     * @param clinicUid the opaque masterdata Clinic.uid string
     * @return list of User uid strings for clinicians affiliated with the given clinic
     */
    List<String> clinicianUserUidsForClinic(String clinicUid);
}
