package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.MdCurrencyDto;
import com.otapp.hmis.masterdata.domain.MdCurrency;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link MdCurrency} (ADR-0014 §3). Package-private; no business logic.
 *
 * <p>Entity field {@code defaultCurrency} → DTO field {@code defaultCurrency}: both sides use
 * the same name so MapStruct resolves automatically. The {@code isXxx} boolean-accessor
 * naming issue is avoided by naming the field {@code defaultCurrency} (not {@code isDefault}).
 */
@Mapper
interface MdCurrencyMapper {

    MdCurrencyDto toDto(MdCurrency entity);

    List<MdCurrencyDto> toDtoList(List<MdCurrency> entities);
}
