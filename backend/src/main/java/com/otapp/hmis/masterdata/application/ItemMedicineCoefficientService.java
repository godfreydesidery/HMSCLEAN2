package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ItemMedicineCoefficientDto;
import com.otapp.hmis.masterdata.application.dto.ItemMedicineCoefficientRequest;
import com.otapp.hmis.masterdata.domain.Item;
import com.otapp.hmis.masterdata.domain.ItemMedicineCoefficient;
import com.otapp.hmis.masterdata.domain.ItemMedicineCoefficientRepository;
import com.otapp.hmis.masterdata.domain.ItemRepository;
import com.otapp.hmis.masterdata.domain.Medicine;
import com.otapp.hmis.masterdata.domain.MedicineRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.HmisException;
import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link ItemMedicineCoefficient} management (build-spec §1.2, §5.3).
 *
 * <p><b>Coefficient computation (build-spec §5.3):</b>
 * {@code coefficient = medicineQty / itemQty} with {@code scale=6, RoundingMode.HALF_UP}.
 * This reproduces the legacy computation in {@code ConversionCoefficientResource.java:95,101}.
 *
 * <p><b>Validation rules (ConversionCoefficientResource.java:87-89):</b>
 * <ul>
 *   <li>{@code itemQty <= 0} or {@code medicineQty <= 0} → 400 "Zero values are not allowed"
 *   <li>Duplicate {@code (item, medicine)} pair → 409 "Coefficient already exist"
 * </ul>
 *
 * <p>Write mutations gated {@code ADMIN-ACCESS} (CR-15 DEVIATION-2 — legacy gate was commented
 * out referencing dead {@code ROLE-CREATE}).
 */
@Service
@RequiredArgsConstructor
public class ItemMedicineCoefficientService {

    static final int COEFFICIENT_SCALE = 6;
    static final RoundingMode COEFFICIENT_ROUNDING = RoundingMode.HALF_UP;

    private final ItemMedicineCoefficientRepository repository;
    private final ItemRepository itemRepository;
    private final MedicineRepository medicineRepository;
    private final ItemMedicineCoefficientMapper mapper;
    private final AuditRecorder auditRecorder;

    @Transactional
    public ItemMedicineCoefficientDto create(ItemMedicineCoefficientRequest request) {
        validateQuantities(request.itemQty(), request.medicineQty());

        Item item = resolveItem(request.itemUid());
        Medicine medicine = resolveMedicine(request.medicineUid());

        // Duplicate check (ConversionCoefficientResource.java:83-86)
        if (repository.findByItemAndMedicine(item, medicine).isPresent()) {
            throw new DuplicateCoefficientException("Coefficient already exist");
        }

        BigDecimal coefficient = computeCoefficient(request.medicineQty(), request.itemQty());

        ItemMedicineCoefficient imc = new ItemMedicineCoefficient(
                item, medicine, request.itemQty(), request.medicineQty(), coefficient);
        repository.save(imc);
        auditRecorder.record("masterdata.ItemMedicineCoefficient", imc.getUid(), AuditAction.CREATE);
        return mapper.toDto(imc);
    }

    @Transactional
    public ItemMedicineCoefficientDto update(String uid, ItemMedicineCoefficientRequest request) {
        validateQuantities(request.itemQty(), request.medicineQty());

        ItemMedicineCoefficient imc = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ItemMedicineCoefficient not found: " + uid));

        // Resolve FKs for duplicate check — must match this existing record's item/medicine
        Item item = resolveItem(request.itemUid());
        Medicine medicine = resolveMedicine(request.medicineUid());

        // Ensure the FK pair matches the stored (immutable) pair — FKs are updatable=false
        if (!imc.getItem().getUid().equals(item.getUid())
                || !imc.getMedicine().getUid().equals(medicine.getUid())) {
            throw new DuplicateCoefficientException("Coefficient already exist");
        }

        BigDecimal coefficient = computeCoefficient(request.medicineQty(), request.itemQty());
        imc.update(request.itemQty(), request.medicineQty(), coefficient);
        auditRecorder.record("masterdata.ItemMedicineCoefficient", imc.getUid(), AuditAction.UPDATE);
        return mapper.toDto(imc);
    }

    @Transactional(readOnly = true)
    public ItemMedicineCoefficientDto get(String uid) {
        return mapper.toDto(repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ItemMedicineCoefficient not found: " + uid)));
    }

    @Transactional(readOnly = true)
    public List<ItemMedicineCoefficientDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByItemNameAsc());
    }

    @Transactional(readOnly = true)
    public List<ItemMedicineCoefficientDto> listByMedicine(String medicineUid) {
        Medicine medicine = resolveMedicine(medicineUid);
        return mapper.toDtoList(repository.findAllByMedicine(medicine));
    }

    // -------------------------------------------------------------------------
    // Business logic helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that both qtys are strictly positive.
     * Reproduces legacy {@code ConversionCoefficientResource.java:87-89}:
     * {@code if(itemQty <= 0 || medicineQty <= 0) throw InvalidEntryException("Zero values are not allowed")}
     */
    private void validateQuantities(BigDecimal itemQty, BigDecimal medicineQty) {
        if (itemQty == null || itemQty.compareTo(BigDecimal.ZERO) <= 0
                || medicineQty == null || medicineQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ZeroCoefficientQuantityException("Zero values are not allowed");
        }
    }

    /**
     * Computes {@code coefficient = medicineQty / itemQty} with scale 6, HALF_UP rounding
     * (build-spec §5.3 / data-architect confirmed NUMERIC(19,6) precision). The stored coefficient
     * is a display/reference value; cross-unit quantity conversion MUST use {@link #convert} (which
     * is lossless), never this rounded value.
     *
     * <p>Golden-master: itemQty=3, medicineQty=1 → {@code 0.333333} (not 0.3333).
     */
    public static BigDecimal computeCoefficient(BigDecimal medicineQty, BigDecimal itemQty) {
        return medicineQty.divide(itemQty, COEFFICIENT_SCALE, COEFFICIENT_ROUNDING);
    }

    /**
     * Converts a quantity across the item/medicine unit boundary WITHOUT the precision loss of the
     * stored 6-dp coefficient: {@code result = qty × medicineQty / itemQty}, rounded HALF_UP at
     * scale 6 only at the END (build-spec §5.3, AC-3b). This is the parity-critical path: the legacy
     * used doubles where {@code 3.0 × (1.0/3.0) == 1.0}, so multiplying by the pre-rounded
     * NUMERIC(19,6) coefficient ({@code 0.333333}) would instead yield {@code 0.999999}. Later
     * increments (pharmacy/store transfers) MUST convert via this method, never via the stored
     * coefficient column.
     *
     * <p>Golden-master: {@code convert(3, medicineQty=1, itemQty=3) == 1.000000} exactly.
     */
    public static BigDecimal convert(BigDecimal qty, BigDecimal medicineQty, BigDecimal itemQty) {
        return qty.multiply(medicineQty).divide(itemQty, COEFFICIENT_SCALE, COEFFICIENT_ROUNDING);
    }

    private Item resolveItem(String uid) {
        return itemRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Item not found: " + uid));
    }

    private Medicine resolveMedicine(String uid) {
        return medicineRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Medicine not found: " + uid));
    }

    // -------------------------------------------------------------------------
    // Typed exceptions (mapped by GlobalExceptionHandler via HmisException)
    // -------------------------------------------------------------------------

    /**
     * Thrown when {@code itemQty <= 0} or {@code medicineQty <= 0}.
     * Maps to HTTP 400 via {@link ErrorCode#VALIDATION}.
     * (Legacy: {@code InvalidEntryException("Zero values are not allowed")} —
     * ConversionCoefficientResource.java:87-89)
     */
    public static final class ZeroCoefficientQuantityException extends HmisException {
        public ZeroCoefficientQuantityException(String detail) {
            super(ErrorCode.VALIDATION, detail);
        }
    }

    /**
     * Thrown when a duplicate {@code (item, medicine)} pair is detected.
     * Maps to HTTP 409 via {@link ErrorCode#CONFLICT}.
     * (Legacy: {@code InvalidOperationException("Coefficient already exist...")} —
     * ConversionCoefficientResource.java:83-86)
     */
    public static final class DuplicateCoefficientException extends HmisException {
        public DuplicateCoefficientException(String detail) {
            super(ErrorCode.CONFLICT, detail);
        }
    }
}
