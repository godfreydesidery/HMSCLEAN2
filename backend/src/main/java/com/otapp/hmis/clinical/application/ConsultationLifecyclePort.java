package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationDto;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary between {@code clinical.web} and {@code clinical.application}.
 *
 * <p>The web layer ({@code ConsultationController}) lives in {@code clinical.web} and cannot
 * reference the package-private {@code ConsultationLifecycleService} directly. This interface
 * is the only public type in {@code clinical.application} that the controller may depend on.
 * The implementation ({@code ConsultationLifecycleService}) remains package-private.
 *
 * <p>Spring wires the package-private impl via component scanning; the web layer holds only
 * this interface reference (ADR-0014 §5 — controller delegates to service, never to repo).
 */
public interface ConsultationLifecyclePort {

    ConsultationDto getByUid(String uid);

    ConsultationDto open(String uid, TxAuditContext ctx);

    ConsultationDto openFollowUp(String uid, TxAuditContext ctx);

    ConsultationDto cancel(String uid, TxAuditContext ctx);

    ConsultationDto free(String uid, TxAuditContext ctx);

    ConsultationDto switchToNormal(String uid, TxAuditContext ctx);

    List<ConsultationDto> receptionQueue(String clinicianUserUid);
}
