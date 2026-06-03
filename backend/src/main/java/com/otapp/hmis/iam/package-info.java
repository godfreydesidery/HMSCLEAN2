/**
 * Identity &amp; Access Management (ADR-0006): User -&gt; Role -&gt; Privilege, self-issued HS256 JWT
 * resource server, login + refresh-token rotation, personnel extensions, and user administration.
 *
 * <p>Exposes the named interface {@code "lookup"} ({@link com.otapp.hmis.iam.lookup}) for
 * cross-module consumption. All other internal types ({@code domain}, {@code application},
 * {@code config}, {@code api}) are module-private.
 *
 * <p>Other modules must depend only on {@code com.otapp.hmis.iam.lookup} types — never on
 * {@code com.otapp.hmis.iam.domain} entities (enforced by IamNoEntityLeakArchTest).
 */
@org.springframework.modulith.ApplicationModule
package com.otapp.hmis.iam;
