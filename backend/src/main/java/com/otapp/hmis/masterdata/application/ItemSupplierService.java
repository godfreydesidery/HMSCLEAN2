package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ItemSupplierDto;
import com.otapp.hmis.masterdata.application.dto.ItemSupplierRequest;
import com.otapp.hmis.masterdata.domain.Item;
import com.otapp.hmis.masterdata.domain.ItemRepository;
import com.otapp.hmis.masterdata.domain.ItemSupplier;
import com.otapp.hmis.masterdata.domain.ItemSupplierRepository;
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
 * Application service for {@link ItemSupplier} management (build-spec §1.2).
 *
 * <p>FK uids ({@code itemUid}, {@code supplierUid}) are resolved to entities inside
 * the service; a {@link NotFoundException} is thrown for any unknown uid.
 */
@Service
@RequiredArgsConstructor
public class ItemSupplierService {

    private final ItemSupplierRepository repository;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final ItemSupplierMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public ItemSupplierDto create(ItemSupplierRequest request) {
        Item item = resolveItem(request.itemUid());
        Supplier supplier = resolveSupplier(request.supplierUid());
        // RF-4: null active defaults to true (legacy ItemSupplier.java:49)
        boolean active = !Boolean.FALSE.equals(request.active());
        ItemSupplier is = new ItemSupplier(
                item, supplier,
                request.costPriceVatIncl(), request.costPriceVatExcl(),
                active);
        repository.save(is);
        auditRecorder.record("masterdata.ItemSupplier", is.getUid(), AuditAction.CREATE);
        return mapper.toDto(is);
    }

    @Transactional
    public ItemSupplierDto update(String uid, ItemSupplierRequest request) {
        ItemSupplier is = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ItemSupplier not found: " + uid));
        // RF-4: null active defaults to true (legacy ItemSupplier.java:49)
        boolean active = !Boolean.FALSE.equals(request.active());
        is.update(request.costPriceVatIncl(), request.costPriceVatExcl(), active);
        auditRecorder.record("masterdata.ItemSupplier", is.getUid(), AuditAction.UPDATE);
        return mapper.toDto(is);
    }

    @Transactional(readOnly = true)
    public ItemSupplierDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ItemSupplier not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<ItemSupplierDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByItemNameAsc());
    }

    @Transactional(readOnly = true)
    public List<ItemSupplierDto> listByItem(String itemUid) {
        Item item = resolveItem(itemUid);
        return mapper.toDtoList(repository.findAllByItem(item));
    }

    // -------------------------------------------------------------------------
    private Item resolveItem(String uid) {
        return itemRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Item not found: " + uid));
    }

    private Supplier resolveSupplier(String uid) {
        return supplierRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + uid));
    }
}
