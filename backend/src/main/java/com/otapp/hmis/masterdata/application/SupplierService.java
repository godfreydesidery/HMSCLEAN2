package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.SupplierDto;
import com.otapp.hmis.masterdata.application.dto.SupplierRequest;
import com.otapp.hmis.masterdata.domain.Supplier;
import com.otapp.hmis.masterdata.domain.SupplierRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Supplier} catalog management (build-spec §1.2, §3).
 *
 * <p>Write mutations gated {@code ADMIN-ACCESS} (CR-15 DEVIATION-3 — legacy had
 * {@code ADMIN-ACCESS} commented out; re-enabled per build-spec §3).
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository repository;
    private final SupplierMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public SupplierDto create(SupplierRequest request) {
        Supplier supplier = new Supplier(
                request.code(), request.name(), request.contactName(), request.active(),
                request.tin(), request.vrn(), request.termsOfContract(),
                request.physicalAddress(), request.postCode(), request.postAddress(),
                request.telephone(), request.mobile(), request.email(), request.fax(),
                request.bankAccountName(), request.bankPhysicalAddress(),
                request.bankPostCode(), request.bankPostAddress(),
                request.bankName(), request.bankAccountNo());
        repository.save(supplier);
        auditRecorder.record("masterdata.Supplier", supplier.getUid(), AuditAction.CREATE);
        return mapper.toDto(supplier);
    }

    @Transactional
    public SupplierDto update(String uid, SupplierRequest request) {
        Supplier supplier = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + uid));
        supplier.update(
                request.code(), request.name(), request.contactName(), request.active(),
                request.tin(), request.vrn(), request.termsOfContract(),
                request.physicalAddress(), request.postCode(), request.postAddress(),
                request.telephone(), request.mobile(), request.email(), request.fax(),
                request.bankAccountName(), request.bankPhysicalAddress(),
                request.bankPostCode(), request.bankPostAddress(),
                request.bankName(), request.bankAccountNo());
        auditRecorder.record("masterdata.Supplier", supplier.getUid(), AuditAction.UPDATE);
        return mapper.toDto(supplier);
    }

    @Transactional(readOnly = true)
    public SupplierDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
