package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MdCurrencyRepository extends JpaRepository<MdCurrency, Long> {

    Optional<MdCurrency> findByUid(String uid);

    Optional<MdCurrency> findByCode(String code);

    boolean existsByCode(String code);

    List<MdCurrency> findAllByOrderByCodeAsc();
}
