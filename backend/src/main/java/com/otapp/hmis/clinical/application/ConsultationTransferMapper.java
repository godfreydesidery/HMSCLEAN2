package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationTransferDto;
import com.otapp.hmis.clinical.domain.ConsultationTransfer;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link ConsultationTransfer} entity to {@link ConsultationTransferDto}
 * (ADR-0014 §3, inc-05 C3).
 *
 * <p>Conventions (ADR-0014 §3):
 * <ul>
 *   <li>Package-private — not part of the module's public API surface.</li>
 *   <li>Spring component model applied globally; CONSTRUCTOR injection strategy.</li>
 *   <li>No repository injection — pure mapping only.</li>
 * </ul>
 *
 * <p>{@code consultationUid} is mapped from the nested {@code consultation.uid} (the intra-module
 * FK is navigable). {@code status} is mapped from the enum name — all three values are valid
 * identifiers so {@code name()} == the persisted string.
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface ConsultationTransferMapper {

    /**
     * Map a {@link ConsultationTransfer} entity to its response DTO.
     *
     * <p>{@code consultationUid} is the intra-module FK, resolved via the JPA association
     * (navigation is allowed within the same module). {@code status} maps from the enum name.
     */
    @Mapping(target = "consultationUid", source = "consultation.uid")
    @Mapping(target = "status", expression = "java(transfer.getStatus().name())")
    ConsultationTransferDto toDto(ConsultationTransfer transfer);
}
