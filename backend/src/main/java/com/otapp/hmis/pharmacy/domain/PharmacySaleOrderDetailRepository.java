package com.otapp.hmis.pharmacy.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PharmacySaleOrderDetail} (inc-08a chunk 4).
 */
public interface PharmacySaleOrderDetailRepository extends JpaRepository<PharmacySaleOrderDetail, Long> {

    Optional<PharmacySaleOrderDetail> findByUid(String uid);

    /** BillSettledEvent seam: find the OTC line linked to a just-paid bill (PatientBillResource.java:369). */
    Optional<PharmacySaleOrderDetail> findByPatientBillUid(String patientBillUid);

    List<PharmacySaleOrderDetail> findByPharmacySaleOrderOrderByCreatedAtAsc(PharmacySaleOrder order);
}
