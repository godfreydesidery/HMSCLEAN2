package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.WardBed;
import com.otapp.hmis.masterdata.domain.WardBedRepository;
import com.otapp.hmis.masterdata.domain.WardTypeRepository;
import com.otapp.hmis.masterdata.lookup.WardBedView;
import com.otapp.hmis.masterdata.lookup.WardLookup;
import com.otapp.hmis.masterdata.lookup.WardTypeView;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage-tier implementation of {@link WardLookup} (inc-07 SEAM-1, ADR-0008 §1).
 *
 * <p>This bean is package-private in {@code masterdata.application}. Other modules consume the
 * {@link WardLookup} interface from {@code masterdata.lookup} — they never reference this class
 * directly (Spring Modulith named-interface contract, ADR-0008).
 *
 * <p>{@code wardTypeUid} is resolved through the chain
 * {@code WardBed → Ward → WardType} — the {@code Ward.wardType} FK is EAGER-fetched (Ward.java:57-60)
 * and {@code WardBed.ward} is also EAGER (WardBed.java:49), so no extra query is issued.
 *
 * <p>All reads are {@code @Transactional(readOnly = true)} — same discipline as
 * {@link PriceLookupImpl}. No writes occur. The transaction boundary ensures a consistent
 * read even when called from a non-transactional context.
 *
 * <p>Legacy citations: WardBed.java:38-55; Ward.java:54-60; WardType.java:31-46.
 * inc-07 SEAM-1 / CR-07-Q3 / ADR-0017 ratified.
 */
@Service
@RequiredArgsConstructor
class WardLookupImpl implements WardLookup {

    private final WardBedRepository wardBedRepository;
    private final WardTypeRepository wardTypeRepository;

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Optional<WardBedView> findBedByUid(String wardBedUid) {
        return wardBedRepository.findByUid(wardBedUid)
                .map(this::toView);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Optional<WardTypeView> findWardTypeByUid(String wardTypeUid) {
        return wardTypeRepository.findByUid(wardTypeUid)
                .map(wt -> new WardTypeView(wt.getUid(), wt.getPrice(), wt.isActive()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean wardBedExists(String wardBedUid) {
        return wardBedRepository.findByUid(wardBedUid).isPresent();
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    /**
     * Map a {@link WardBed} entity to a {@link WardBedView} projection.
     *
     * <p>{@code wardTypeUid} is resolved via {@code bed.getWard().getWardType().getUid()}.
     * Both EAGER FKs are already loaded; no lazy-load is triggered.
     */
    private WardBedView toView(WardBed bed) {
        String wardUid = bed.getWard().getUid();
        String wardTypeUid = bed.getWard().getWardType().getUid();
        return new WardBedView(
                bed.getUid(),
                bed.getStatus(),
                bed.isActive(),
                wardUid,
                wardTypeUid);
    }
}
