package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationLookup;
import com.otapp.hmis.clinical.api.ConsultationWorkStatus;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ConsultationLookup} (ADR-0022 D5, package-private).
 *
 * <p>Bridges the API-level {@link ConsultationWorkStatus} enum (visible to {@code registration})
 * to the domain {@link ConsultationStatus} enum (internal to {@code clinical}).
 *
 * <p>This is a read-only service; called within the registration module's @Transactional
 * boundary (REQUIRED propagation — reads the same snapshot).
 *
 * <p>Legacy citation: PatientResource.java:485-488 (existsByPatientAndStatus(patient, PENDING))
 * and PatientResource.java:325-357 (open-work check in change_payment_type).
 */
@Service
@RequiredArgsConstructor
class ConsultationLookupImpl implements ConsultationLookup {

    private final ConsultationRepository consultationRepository;

    /**
     * {@inheritDoc}
     *
     * <p>Maps each {@link ConsultationWorkStatus} API-enum value to the corresponding
     * {@link ConsultationStatus} domain-enum value, then delegates to the repository.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenWork(String patientUid, Set<ConsultationWorkStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return false;
        }
        Set<ConsultationStatus> domainStatuses = statuses.stream()
                .map(ConsultationLookupImpl::toDomainStatus)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ConsultationStatus.class)));
        return consultationRepository.existsByPatientUidAndStatusIn(patientUid, domainStatuses);
    }

    /**
     * Map API-enum to domain-enum (ADR-0022 D5 — shields registration from domain enum).
     */
    private static ConsultationStatus toDomainStatus(ConsultationWorkStatus ws) {
        return switch (ws) {
            case PENDING    -> ConsultationStatus.PENDING;
            case IN_PROCESS -> ConsultationStatus.IN_PROCESS;
            case TRANSFERED -> ConsultationStatus.TRANSFERED;
        };
    }
}
