/**
 * Identity &amp; Access Management (ADR-0006): User -&gt; Role -&gt; Privilege, self-issued HS256 JWT
 * resource server, login + refresh-token rotation. Publishes nothing cross-module in increment 00
 * beyond being an allowed dependency of {@code masterdata}.
 */
@org.springframework.modulith.ApplicationModule
package com.otapp.hmis.iam;
