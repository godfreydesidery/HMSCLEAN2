package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.PatientInvoiceDto;
import com.otapp.hmis.billing.domain.PatientInvoice;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link PatientInvoice} (ADR-0014 §3).
 * Package-private; no business logic. Includes nested detail lines.
 */
@Mapper(uses = PatientInvoiceDetailMapper.class,
        injectionStrategy = org.mapstruct.InjectionStrategy.CONSTRUCTOR)
interface PatientInvoiceMapper {

    @Mapping(target = "details", source = "details")
    PatientInvoiceDto toDto(PatientInvoice entity);

    List<PatientInvoiceDto> toDtoList(List<PatientInvoice> entities);
}
