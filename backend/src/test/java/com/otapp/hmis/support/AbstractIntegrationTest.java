package com.otapp.hmis.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Single base class for all {@code *IT} integration tests (ADR-0014 §10). Boots the full Spring
 * context against a real PostgreSQL 16 via Testcontainers using {@code @ServiceConnection}; Flyway
 * applies V1–V3 on startup and Hibernate {@code ddl-auto=validate} confirms entity/schema parity.
 *
 * <p>Uses the <b>singleton container</b> pattern: a single Postgres container is started once in a
 * static initializer and shared by every IT class. This is started independently of the JUnit
 * {@code @Testcontainers}/{@code @Container} per-class lifecycle on purpose — that lifecycle stops
 * the container after each class, which collides with Spring's cross-class context cache (a reused
 * context would point at a stopped container). The container is reaped at JVM exit by Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }
}
