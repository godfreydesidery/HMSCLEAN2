package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.PatientInvoiceDetailDto;
import com.otapp.hmis.billing.domain.PatientInvoiceDetail;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link PatientInvoiceDetail}.
 * Package-private; no business logic. Maps bill uid via the nested bill entity.
 */
@Mapper
interface PatientInvoiceDetailMapper {

    @Mapping(target = "billUid", source = "bill.uid")
    PatientInvoiceDetailDto toDto(PatientInvoiceDetail entity);

    List<PatientInvoiceDetailDto> toDtoList(List<PatientInvoiceDetail> entities);
}
