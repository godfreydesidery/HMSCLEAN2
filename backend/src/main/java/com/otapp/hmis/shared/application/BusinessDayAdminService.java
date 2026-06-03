package com.otapp.hmis.shared.application;

import com.otapp.hmis.shared.application.dto.BusinessDayDto;
import com.otapp.hmis.shared.domain.BusinessDay;
import com.otapp.hmis.shared.domain.BusinessDayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Thin application facade that wraps {@link BusinessDayService} and maps results to
 * {@link BusinessDayDto} for the admin controller (build-spec §5, P5).
 *
 * <p>Keeps the package-private {@link BusinessDayMapper} inside {@code shared.application}
 * where it belongs — the controller in {@code shared.api} never touches the mapper directly.
 */
@Service
@RequiredArgsConstructor
public class BusinessDayAdminService {

    private final BusinessDayService domainService;
    private final BusinessDayMapper mapper;

    public BusinessDayDto openToday() {
        BusinessDay day = domainService.openToday();
        return mapper.toDto(day);
    }

    public BusinessDayDto closeCurrentDay() {
        BusinessDay day = domainService.closeCurrentDay();
        return mapper.toDto(day);
    }

    public BusinessDayDto currentDay() {
        BusinessDay day = domainService.currentDay();
        return mapper.toDto(day);
    }
}
