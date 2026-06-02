package com.otapp.hmis.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.otapp.hmis.shared.domain.BusinessDay;
import com.otapp.hmis.shared.domain.BusinessDayRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BusinessDayServiceTest {

    @Mock
    BusinessDayRepository repository;

    @Test
    void currentUidThrowsNoDayOpenWhenNoOpenDay() {
        BusinessDayService service = new BusinessDayService(repository);
        when(repository.findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status.OPEN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(service::currentUid)
                .isInstanceOf(NoDayOpenException.class)
                .satisfies(ex -> assertThat(((NoDayOpenException) ex).errorCode())
                        .isEqualTo(ErrorCode.NO_DAY_OPEN));
    }

    @Test
    void noDayOpenMapsToUnprocessableEntityWithStableType() {
        NoDayOpenException ex = new NoDayOpenException();
        assertThat(ex.errorCode().type()).isEqualTo("urn:hmis:error:no-day-open");
        assertThat(ex.errorCode().status().value()).isEqualTo(422);
    }

    @Test
    void currentUidReturnsOpenDayUid() {
        BusinessDay day = BusinessDay.open(LocalDate.of(2026, 6, 2), Instant.now());
        // uid is assigned at @PrePersist in production; set it explicitly here (no public setter).
        String uid = "01HQZ8K9M3N7P2R4S6T8V0W2X4";
        ReflectionTestUtils.setField(day, "uid", uid);
        when(repository.findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status.OPEN))
                .thenReturn(Optional.of(day));

        BusinessDayService service = new BusinessDayService(repository);
        assertThat(service.currentUid()).isEqualTo(uid);
    }
}
