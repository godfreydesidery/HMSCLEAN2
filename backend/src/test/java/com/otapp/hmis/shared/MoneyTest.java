package com.otapp.hmis.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.shared.application.MoneyMapper;
import com.otapp.hmis.shared.application.dto.MoneyDto;
import com.otapp.hmis.shared.domain.Money;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class MoneyTest {

    @Test
    void roundsHalfUpToScaleTwo() {
        Money money = Money.of(new BigDecimal("10.005"));
        assertThat(money.getAmount()).isEqualByComparingTo(new BigDecimal("10.01"));
        assertThat(money.getAmount().scale()).isEqualTo(2);
        assertThat(money.getCurrency()).isEqualTo("TZS");
    }

    @Test
    void amountColumnDeclaresNumeric19Scale2() throws NoSuchFieldException {
        Field amount = Money.class.getDeclaredField("amount");
        Column column = amount.getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.precision()).isEqualTo(19);
        assertThat(column.scale()).isEqualTo(2);
    }

    @Test
    void halfUpRoundingModeIsApplied() {
        // 2.345 -> 2.35 (HALF_UP), not 2.34 (HALF_EVEN would give 2.34)
        Money money = Money.of(new BigDecimal("2.345"));
        assertThat(money.getAmount()).isEqualByComparingTo(new BigDecimal("2.35"));
        assertThat(RoundingMode.HALF_UP).isNotNull();
    }

    @Test
    void mapperRoundTripsWithoutId() {
        MoneyMapper mapper = Mappers.getMapper(MoneyMapper.class);
        Money money = Money.of(new BigDecimal("99.99"), "USD");
        MoneyDto dto = mapper.toDto(money);
        assertThat(dto.amount()).isEqualByComparingTo(new BigDecimal("99.99"));
        assertThat(dto.currency()).isEqualTo("USD");

        Money back = mapper.toDomain(dto);
        assertThat(back).isEqualTo(money);

        // MoneyDto must not expose an id field (ADR-0014 §1).
        assertThat(MoneyDto.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("id");
    }
}
