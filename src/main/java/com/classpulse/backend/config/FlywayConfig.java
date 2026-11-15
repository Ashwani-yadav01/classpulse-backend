package com.classpulse.backend.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration class for customizing Flyway database migration lifecycle.
 * <p>
 * In heterogeneous development environments (e.g. Windows vs Linux/macOS), Git line endings
 * (CRLF vs LF) can alter SQL script checksums. This configuration installs a custom
 * {@link FlywayMigrationStrategy} to automatically run a repair step prior to migration.
 */
@Configuration
public class FlywayConfig {

    /**
     * Provides a custom {@link FlywayMigrationStrategy} executed during Spring Boot startup.
     * <p>
     * Sequence of operations:
     * <ol>
     *   <li>{@code flyway.repair()}: Repairs the schema history table, resolving checksum or description
     *       mismatches caused by git line ending conversions or formatting tweaks without manual intervention.</li>
     *   <li>{@code flyway.migrate()}: Discovers and applies all pending versioned SQL migrations in order.</li>
     * </ol>
     *
     * @return a customized migration strategy lambda
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            // Step 1: Repair the Flyway schema history table to fix line-ending checksum discrepancies
            flyway.repair();

            // Step 2: Apply all remaining unapplied database migrations
            flyway.migrate();
        };
    }
}
