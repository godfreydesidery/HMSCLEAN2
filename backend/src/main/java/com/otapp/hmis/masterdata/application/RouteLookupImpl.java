package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.AdministrationRouteRepository;
import com.otapp.hmis.masterdata.lookup.RouteLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link RouteLookup} (inc-07 07d, CR-07-MAR).
 */
@Service
@RequiredArgsConstructor
class RouteLookupImpl implements RouteLookup {

    private final AdministrationRouteRepository administrationRouteRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveRoute(String routeUid) {
        return administrationRouteRepository.findByUid(routeUid)
                .filter(com.otapp.hmis.masterdata.domain.AdministrationRoute::isActive)
                .isPresent();
    }
}
