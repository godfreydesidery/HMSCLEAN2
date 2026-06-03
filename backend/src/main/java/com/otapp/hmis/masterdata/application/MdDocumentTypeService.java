package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.MdDocumentTypeDto;
import com.otapp.hmis.masterdata.domain.MdDocumentTypeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link com.otapp.hmis.masterdata.domain.MdDocumentType}
 * (build-spec §1.5, CR-09, CR-10).
 *
 * <p>Inc-02 exposes read-only access. Rows are seeded via V14 migration.
 * No create/update endpoint in this increment — prefix management deferred to a future UI.
 */
@Service
@RequiredArgsConstructor
public class MdDocumentTypeService {

    private final MdDocumentTypeRepository repository;
    private final MdDocumentTypeMapper mapper;

    @Transactional(readOnly = true)
    public List<MdDocumentTypeDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByKindAsc());
    }
}
