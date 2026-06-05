package com.otapp.hmis.shared.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling + distributed-lock configuration (ADR-0018 §Decision §1, inc-07 07c-ii / CR-07-Q2).
 *
 * <p>Enables Spring {@code @Scheduled} and ShedLock {@code @SchedulerLock}. ShedLock guarantees a
 * scheduled method runs on AT MOST ONE node at a time by acquiring a named lock row in the
 * {@code shedlock} table (V50) before execution — closing the multi-node double-execution defect
 * that was unresolved in the prior attempt (ADR-0018 §Context).
 *
 * <p><strong>Disableable for tests</strong> (gated on {@code hmis.scheduling.enabled}, default
 * {@code true}): integration tests set {@code hmis.scheduling.enabled=false} so the ward-accrual
 * cron does NOT fire during the suite — the ITs drive {@code WardAccrualService.accrueWardDay}
 * directly. When disabled, neither {@code @EnableScheduling} nor the lock provider is registered,
 * so no {@code @Scheduled} method (and no ShedLock JDBC access) is wired.
 *
 * <p>{@code defaultLockAtMostFor = "PT5M"} (ADR-0018 §Implementation-notes 3); per-job overrides
 * are on the {@code @SchedulerLock} annotation (ward-accrual uses PT10M).
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@ConditionalOnProperty(name = "hmis.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    /**
     * ShedLock JDBC lock provider over the application {@link DataSource}, backed by the
     * {@code shedlock} table (V50). Per the ShedLock JdbcTemplateLockProvider documentation.
     *
     * @param dataSource the application datasource
     * @return the configured {@link LockProvider}
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime() // use DB clock for lock_until (avoids node clock skew)
                        .build());
    }
}
