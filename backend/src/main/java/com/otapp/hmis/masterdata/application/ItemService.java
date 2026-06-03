package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ItemDto;
import com.otapp.hmis.masterdata.application.dto.ItemRequest;
import com.otapp.hmis.masterdata.domain.Item;
import com.otapp.hmis.masterdata.domain.ItemRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Item} catalog management (build-spec §1.2, §3).
 *
 * <p>Write mutations gated {@code ADMIN-ACCESS} (CR-15 DEVIATION-1 — legacy gate was commented
 * out referencing dead {@code PROCUREMENT-ACCESS}; re-gated to {@code ADMIN-ACCESS}).
 */
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository repository;
    private final ItemMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public ItemDto create(ItemRequest request) {
        Item item = new Item(
                request.code(),
                request.barcode(),
                request.name(),
                request.shortName(),
                request.commonName(),
                request.vat(),
                request.uom(),
                request.packSize(),
                request.category(),
                request.costPriceVatIncl(),
                request.sellingPriceVatIncl(),
                request.active(),
                request.ingredients());
        repository.save(item);
        auditRecorder.record("masterdata.Item", item.getUid(), AuditAction.CREATE);
        return mapper.toDto(item);
    }

    @Transactional
    public ItemDto update(String uid, ItemRequest request) {
        Item item = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Item not found: " + uid));
        item.update(
                request.code(),
                request.barcode(),
                request.name(),
                request.shortName(),
                request.commonName(),
                request.vat(),
                request.uom(),
                request.packSize(),
                request.category(),
                request.costPriceVatIncl(),
                request.sellingPriceVatIncl(),
                request.active(),
                request.ingredients());
        auditRecorder.record("masterdata.Item", item.getUid(), AuditAction.UPDATE);
        return mapper.toDto(item);
    }

    @Transactional(readOnly = true)
    public ItemDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Item not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<ItemDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByNameAsc());
    }
}
