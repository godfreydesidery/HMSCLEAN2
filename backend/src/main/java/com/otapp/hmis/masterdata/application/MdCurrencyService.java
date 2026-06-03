package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.MdCurrencyDto;
import com.otapp.hmis.masterdata.application.dto.MdCurrencyRequest;
import com.otapp.hmis.masterdata.domain.MdCurrency;
import com.otapp.hmis.masterdata.domain.MdCurrencyRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link MdCurrency} (build-spec §1.5).
 *
 * <p>Writes are gated {@code ADMIN-ACCESS} at the controller. Duplicate {@code code}
 * detection pre-checks via repository before insert so the 409 arrives cleanly without
 * relying on a DB constraint rollback.
 */
@Service
@RequiredArgsConstructor
public class MdCurrencyService {

    private static final String ENTITY_TYPE = "masterdata.MdCurrency";

    private final MdCurrencyRepository repository;
    private final MdCurrencyMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public MdCurrencyDto create(MdCurrencyRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new DuplicateCurrencyCodeException(request.code());
        }
        MdCurrency currency = new MdCurrency(request.code(), request.name(), request.defaultCurrency());
        repository.save(currency);
        auditRecorder.record(ENTITY_TYPE, currency.getUid(), AuditAction.CREATE);
        return mapper.toDto(currency);
    }

    @Transactional(readOnly = true)
    public List<MdCurrencyDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByCodeAsc());
    }

    // ------------------------------------------------------------------
    // Inline exception — scoped to this service; no cross-module throw
    // ------------------------------------------------------------------

    static final class DuplicateCurrencyCodeException extends HmisException {
        DuplicateCurrencyCodeException(String code) {
            super(ErrorCode.CONFLICT, "A currency with code '" + code + "' already exists.");
        }
    }
}
