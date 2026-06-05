package com.otapp.hmis.pharmacy.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link PharmacySaleOrder} (inc-08a chunk 4).
 *
 * <p>{@link #nextPsoNo()} performs the single atomic {@code nextval(seq_pso_no)} for the
 * sequence-backed PSO number (CR-09-NUM1 — replaces the legacy raw-PK suffix that ADR-0014 §1
 * forbids). The service formats it as {@code 'PSO/' + seq}.
 */
public interface PharmacySaleOrderRepository extends JpaRepository<PharmacySaleOrder, Long> {

    Optional<PharmacySaleOrder> findByUid(String uid);

    /** Worklist FILTER: orders in PENDING or APPROVED (legacy PatientServiceImpl.java:3019-3026). */
    List<PharmacySaleOrder> findByStatusInOrderByCreatedAtAsc(List<OtcOrderStatus> statuses);

    /** Stale-order sweep inputs. */
    List<PharmacySaleOrder> findByStatus(OtcOrderStatus status);

    @Query(nativeQuery = true, value = "SELECT nextval('seq_pso_no')")
    Long nextPsoNo();
}
