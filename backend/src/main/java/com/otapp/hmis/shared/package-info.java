/**
 * Shared kernel (ADR-0014). Cross-cutting base entity ({@code AuditableEntity}), {@code Money}
 * value object, audit log + recorder (ADR-0007), business-day, the RFC 7807 error model, and
 * security/JPA configuration. Declared OPEN so any bounded context may depend on it without an
 * explicit allowed-dependency edge.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.otapp.hmis.shared;
