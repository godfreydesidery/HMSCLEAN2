package com.otapp.hmis.shared.application;

import com.otapp.hmis.shared.application.dto.MoneyDto;
import com.otapp.hmis.shared.domain.Money;
import org.mapstruct.Mapper;
import org.springframework.lang.Nullable;

/**
 * Canonical MapStruct mapper (ADR-0014 §3): the project's reference example. No hand-coded mappers
 * anywhere; no business logic in mappers. {@code componentModel = "spring"} is supplied globally via
 * {@code -Amapstruct.defaultComponentModel=spring}.
 *
 * <p>{@link #toDto(Money)} is MapStruct-generated (it reads the Lombok getters and constructs the
 * {@code MoneyDto} record). {@link #toDomain(MoneyDto)} is a {@code default} method because
 * {@link Money} is immutable and built through a rounding factory MapStruct cannot invoke directly.
 */
@Mapper
public interface MoneyMapper {

    @Nullable
    MoneyDto toDto(@Nullable Money money);

    @Nullable
    default Money toDomain(@Nullable MoneyDto dto) {
        if (dto == null) {
            return null;
        }
        String currency = dto.currency() != null ? dto.currency() : Money.DEFAULT_CURRENCY;
        return Money.of(dto.amount(), currency);
    }
}
