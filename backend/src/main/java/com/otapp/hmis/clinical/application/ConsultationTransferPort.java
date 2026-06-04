package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationTransferDto;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary between {@code clinical.web} and
 * {@code clinical.application.ConsultationTransferService}.
 *
 * <p>The web layer ({@code ConsultationTransferController}) lives in {@code clinical.web} and
 * cannot reference the package-private {@code ConsultationTransferService} directly. This
 * interface is the only public type in {@code clinical.application} the transfer controller
 * may depend on. The implementation remains package-private.
 *
 * <p>Follows the same pattern as {@link ConsultationLifecyclePort}.
 */
public interface ConsultationTransferPort {

    /**
     * Raise a transfer: IN_PROCESS source consultation → TRANSFERED; create PENDING transfer.
     *
     * @param consultationUid     source consultation ULID
     * @param destinationClinicUid destination clinic loose uid (masterdata)
     * @param reason              free-text rationale (nullable)
     * @param ctx                 transaction audit context
     * @return the created ConsultationTransferDto
     */
    ConsultationTransferDto raise(String consultationUid, String destinationClinicUid,
                                   String reason, TxAuditContext ctx);

    /**
     * Cancel a PENDING transfer for a consultation.
     *
     * <p>If the source consultation is not TRANSFERED: silent no-op return (legacy parity).
     *
     * @param consultationUid source consultation ULID
     * @param ctx             transaction audit context
     * @return the updated (or unchanged) ConsultationTransferDto, or null if no transfer found
     */
    ConsultationTransferDto cancelByConsultation(String consultationUid, TxAuditContext ctx);

    /**
     * System-wide pending-transfer queue — all PENDING transfers, unscoped.
     *
     * <p>Legacy parity: PatientResource.java:599 — findAllByStatus PENDING, no filters.
     *
     * @return all PENDING transfers
     */
    List<ConsultationTransferDto> listPending();
}
