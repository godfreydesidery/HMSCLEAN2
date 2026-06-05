package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.clinical.api.PrescriptionPatientType;
import com.otapp.hmis.clinical.api.PrescriptionView;
import com.otapp.hmis.clinical.api.PrescriptionWorklistPort;
import com.otapp.hmis.pharmacy.application.dto.StockBatchDto;
import com.otapp.hmis.pharmacy.application.dto.StockMovementDto;
import com.otapp.hmis.pharmacy.application.dto.StockStatusDto;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicine;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.StockBatchRepository;
import com.otapp.hmis.pharmacy.domain.StockMovementRepository;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pharmacy read/query surface (inc-08a chunk 3): the dispense worklist (delegates the legacy
 * bill-status FILTER to {@link PrescriptionWorklistPort}) plus stock-status / batch / stock-card
 * reads. All reads scope by {@code pharmacyUid} (Q2 — a FILTER, never a hard gate, N13).
 */
@Service
@RequiredArgsConstructor
public class PharmacyStockQueryService {

    private final PrescriptionWorklistPort worklistPort;
    private final PharmacyMedicineRepository pharmacyMedicineRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StockMovementRepository stockMovementRepository;

    /**
     * Pharmacy dispense worklist — NOT-GIVEN prescriptions admitted by the legacy bill-status FILTER
     * for the patient type (PAID|COVERED; +VERIFIED for INPATIENT). Delegated to clinical::api.
     */
    @Transactional(readOnly = true)
    public List<PrescriptionView> worklist(PrescriptionPatientType patientType, String patientUid) {
        return worklistPort.dispenseWorklist(
                new PrescriptionWorklistPort.WorklistFilter(patientType, patientUid));
    }

    /** Stock status for every medicine in a pharmacy (scalar aggregate balances). */
    @Transactional(readOnly = true)
    public List<StockStatusDto> stockStatus(String pharmacyUid) {
        return pharmacyMedicineRepository.findByPharmacyUidOrderByMedicineUidAsc(pharmacyUid).stream()
                .map(pm -> new StockStatusDto(
                        pm.getUid(), pm.getPharmacyUid(), pm.getMedicineUid(), pm.getStock()))
                .toList();
    }

    /** FEFO lots for one (pharmacy, medicine), ordered as the FEFO walk would consume them. */
    @Transactional(readOnly = true)
    public List<StockBatchDto> batches(String pharmacyUid, String medicineUid) {
        PharmacyMedicine pm = requireAggregate(pharmacyUid, medicineUid);
        return stockBatchRepository.findByPharmacyMedicine(pm).stream()
                .map(b -> new StockBatchDto(
                        b.getUid(), b.getPharmacyUid(), b.getMedicineUid(), b.getBatchNo(),
                        b.getManufacturedDate(), b.getExpiryDate(),
                        b.getReceivedQty(), b.getRemainingQty()))
                .toList();
    }

    /** Stock-card (movement ledger) for one (pharmacy, medicine), oldest first. */
    @Transactional(readOnly = true)
    public List<StockMovementDto> movements(String pharmacyUid, String medicineUid) {
        return stockMovementRepository
                .findByPharmacyUidAndMedicineUidOrderByOccurredAtAsc(pharmacyUid, medicineUid).stream()
                .map(m -> new StockMovementDto(
                        m.getUid(), m.getPharmacyUid(), m.getMedicineUid(),
                        m.getMovementType().name(), m.getQtyIn(), m.getQtyOut(),
                        m.getRunningBalance(), m.getReference(), m.getOccurredAt()))
                .toList();
    }

    private PharmacyMedicine requireAggregate(String pharmacyUid, String medicineUid) {
        return pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid)
                .orElseThrow(() -> new NotFoundException(
                        "No stock record for medicine in this pharmacy"));
    }
}
