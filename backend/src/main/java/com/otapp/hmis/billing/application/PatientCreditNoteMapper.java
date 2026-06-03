package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.CreditNoteDto;
import com.otapp.hmis.billing.domain.PatientCreditNote;
import com.otapp.hmis.shared.application.MoneyMapper;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link PatientCreditNote} (ADR-0014 §3).
 * Package-private; no business logic. {@code status} (enum) maps to its name automatically.
 */
@Mapper(uses = MoneyMapper.class,
        injectionStrategy = org.mapstruct.InjectionStrategy.CONSTRUCTOR)
interface PatientCreditNoteMapper {

    @Mapping(target = "amount", source = "amount")
    CreditNoteDto toDto(PatientCreditNote entity);

    List<CreditNoteDto> toDtoList(List<PatientCreditNote> entities);
}
