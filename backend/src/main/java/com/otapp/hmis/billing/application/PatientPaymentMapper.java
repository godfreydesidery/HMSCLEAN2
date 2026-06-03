package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.PatientPaymentDto;
import com.otapp.hmis.billing.domain.PatientPayment;
import com.otapp.hmis.shared.application.MoneyMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link PatientPayment} (ADR-0014 §3).
 * Package-private; no business logic.
 */
@Mapper(uses = MoneyMapper.class,
        injectionStrategy = org.mapstruct.InjectionStrategy.CONSTRUCTOR)
interface PatientPaymentMapper {

    @Mapping(target = "amount", source = "amount")
    PatientPaymentDto toDto(PatientPayment entity);
}
