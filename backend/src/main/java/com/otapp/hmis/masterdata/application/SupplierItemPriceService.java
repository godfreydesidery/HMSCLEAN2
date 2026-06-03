package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.SupplierDto;
import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceDto;
import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceListDto;
import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceRequest;
import com.otapp.hmis.masterdata.domain.Item;
import com.otapp.hmis.masterdata.domain.ItemRepository;
import com.otapp.hmis.masterdata.domain.Supplier;
import com.otapp.hmis.masterdata.domain.SupplierItemPrice;
import com.otapp.hmis.masterdata.domain.SupplierItemPriceRepository;
import com.otapp.hmis.masterdata.domain.SupplierRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link SupplierItemPrice} management (build-spec §1.2, §3).
 *
 * <p>Write mutations gated {@code SUPPLIER_PRICE_LIST-ALL} (build-spec §3 gate map — exact legacy).
 * The {@link SupplierItemPriceListDto} is a non-persistent response aggregation.
 */
@Service
@RequiredArgsConstructor
public class SupplierItemPriceService {

    private final SupplierItemPriceRepository repository;
    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;
    private final SupplierItemPriceMapper priceMapper;
    private final SupplierMapper supplierMapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public SupplierItemPriceDto create(SupplierItemPriceRequest request) {
        Supplier supplier = resolveSupplier(request.supplierUid());
        Item item = resolveItem(request.itemUid());
        SupplierItemPrice sip = new SupplierItemPrice(
                request.price(), request.terms(), request.active(), supplier, item);
        repository.save(sip);
        auditRecorder.record("masterdata.SupplierItemPrice", sip.getUid(), AuditAction.CREATE);
        return priceMapper.toDto(sip);
    }

    @Transactional
    public SupplierItemPriceDto update(String uid, SupplierItemPriceRequest request) {
        SupplierItemPrice sip = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("SupplierItemPrice not found: " + uid));
        sip.update(request.price(), request.terms(), request.active());
        auditRecorder.record("masterdata.SupplierItemPrice", sip.getUid(), AuditAction.UPDATE);
        return priceMapper.toDto(sip);
    }

    @Transactional
    public void delete(String uid) {
        SupplierItemPrice sip = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("SupplierItemPrice not found: " + uid));
        auditRecorder.record("masterdata.SupplierItemPrice", sip.getUid(), AuditAction.DELETE);
        repository.delete(sip);
    }

    @Transactional(readOnly = true)
    public SupplierItemPriceDto get(String uid) {
        return priceMapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("SupplierItemPrice not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<SupplierItemPriceDto> list() {
        return priceMapper.toDtoList(repository.findAllByOrderBySupplierNameAsc());
    }

    /**
     * Returns the non-persistent {@link SupplierItemPriceListDto} aggregating all prices
     * for the specified supplier (legacy SupplierItemPriceList response wrapper).
     */
    @Transactional(readOnly = true)
    public SupplierItemPriceListDto listBySupplier(String supplierUid) {
        Supplier supplier = resolveSupplier(supplierUid);
        List<SupplierItemPriceDto> prices = priceMapper.toDtoList(
                repository.findAllBySupplier(supplier));
        SupplierDto supplierDto = supplierMapper.toDto(supplier);
        return new SupplierItemPriceListDto(supplierDto, prices);
    }

    // -------------------------------------------------------------------------
    private Supplier resolveSupplier(String uid) {
        return supplierRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + uid));
    }

    private Item resolveItem(String uid) {
        return itemRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Item not found: " + uid));
    }
}
