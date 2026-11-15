package com.classpulse.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Spring configuration class that provides a centralized, injectable {@link Clock} bean.
 * <p>
 * Centralizing the time provider via dependency injection allows:
 * <ul>
 *   <li>Consistent time resolution across all services (UTC standard).</li>
 *   <li>Deterministic time control in automated unit/integration tests (e.g., using fixed clocks).</li>
 *   <li>Prevention of subtle timezone bugs across distributed server environments.</li>
 * </ul>
 */
@Configuration
public class ClockConfig {

    /**
     * Declares the default system UTC clock bean.
     *
     * @return a {@link Clock} instance running in the UTC time zone
     */
    @Bean
    public Clock clock() {
        // Return a system clock that queries the best available system clock in UTC.
        // All timestamps (session expiry, activity timestamps, response creation) rely on this.
        return Clock.systemUTC();
    }
}
