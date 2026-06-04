package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Cross-module API for booking a consultation (ADR-0022 D3/D5).
 *
 * <p>Called by {@code registration.PatientRegistrationProcess.sendToDoctor} inside the
 * registration's own {@code @Transactional} boundary — propagation REQUIRED (caller's tx),
 * NO {@code @Async}, NO {@code REQUIRES_NEW}. The consultation persist is atomic with the
 * Visit creation and the billing charge.
 *
 * <p>Implementation is package-private in {@code clinical.application.ConsultationBookingServiceImpl}.
 *
 * <p>Legacy citation: PatientServiceImpl.java:494-511 (consultation creation inline in
 * do_consultation; this interface is the modernised boundary after ADR-0022 ownership transfer).
 */
public interface ConsultationBookingService {

    /**
     * Persist a new PENDING consultation and record its CREATE audit.
     *
     * <p>Creates the {@link com.otapp.hmis.clinical.domain.Consultation} with
     * {@code status = PENDING} and the {@code settled} pre-pass value from the command.
     *
     * @param cmd the booking command (all loose uids, no entity references)
     * @param ctx the transaction audit context (dayUid, timestamp, actor)
     * @return the mapped {@link ConsultationDto} for the 201 response
     */
    ConsultationDto book(BookConsultationCommand cmd, TxAuditContext ctx);
}
