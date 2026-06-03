package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MdDocumentTypeRepository extends JpaRepository<MdDocumentType, Long> {

    Optional<MdDocumentType> findByUid(String uid);

    Optional<MdDocumentType> findByKind(String kind);

    boolean existsByPrefix(String prefix);

    List<MdDocumentType> findAllByOrderByKindAsc();
}
