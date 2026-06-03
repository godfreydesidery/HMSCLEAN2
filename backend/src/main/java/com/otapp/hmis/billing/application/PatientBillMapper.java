package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.PatientBillDto;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.shared.application.MoneyMapper;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link PatientBill} (ADR-0014 §3).
 * Package-private; no business logic. Uses the shared {@link MoneyMapper} for Money fields.
 * Maps {@code uid} but never the internal {@code id}.
 */
@Mapper(uses = MoneyMapper.class, unmappedSourcePolicy = org.mapstruct.ReportingPolicy.IGNORE,
        injectionStrategy = org.mapstruct.InjectionStrategy.CONSTRUCTOR)
interface PatientBillMapper {

    @Mapping(target = "amount",  source = "amount")
    @Mapping(target = "paid",    source = "paid")
    @Mapping(target = "balance", source = "balance")
    PatientBillDto toDto(PatientBill entity);

    List<PatientBillDto> toDtoList(List<PatientBill> entities);
}
