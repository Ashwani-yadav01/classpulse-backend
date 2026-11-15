package com.classpulse.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration establishing application security filters, CORS policies,
 * password hashing algorithms, and stateless session management.
 * <p>
 * Fine-grained endpoint authentication (JWT tokens, participant tokens, master keys)
 * is delegated to specialized security services ({@link TeacherSecurityService},
 * {@link ParticipantSecurityService}) and WebSocket interceptors.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Comma-separated list of allowed CORS origins configured in application properties. */
    @Value("${classpulse.cors.allowed-origins:*}")
    private String allowedOrigins;

    /**
     * Declares the standard BCrypt password hashing encoder bean for secure user password storage.
     *
     * @return BCrypt {@link PasswordEncoder} instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Configures the main Spring Security filter chain.
     * <ul>
     *   <li>Disables CSRF (stateless REST APIs relying on tokens).</li>
     *   <li>Enables CORS using the registered CORS configuration source.</li>
     *   <li>Configures STATELESS session creation (no HTTP session cookies maintained).</li>
     *   <li>Permits all inbound HTTP requests to reach custom service-level security checks.</li>
     * </ul>
     *
     * @param http Spring HttpSecurity builder
     * @return constructed SecurityFilterChain
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF protection because APIs use stateless token authorization
            .csrf(AbstractHttpConfigurer::disable)
            // Register custom CORS filter
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Enforce stateless session management to prevent HTTP session state creation
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Route authorization: all endpoints permitted at network level; protected at method level
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().permitAll()
            );

        return http.build();
    }

    /**
     * Defines Cross-Origin Resource Sharing (CORS) rules allowing web clients
     * (e.g. Next.js, React frontend applications) to interact with the backend APIs and WebSockets.
     *
     * @return configured {@link CorsConfigurationSource}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Check if origins are wildcarded or specified as comma-delimited hosts
        if (allowedOrigins == null || allowedOrigins.isBlank() || "*".equals(allowedOrigins.trim())) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            List<String> origins = Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            configuration.setAllowedOrigins(origins);
        }

        // Allow all standard HTTP methods
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Allow all client headers (e.g. Authorization, X-Teacher-Key, X-Participant-Token)
        configuration.setAllowedHeaders(List.of("*"));
        // Allow cookie/credential passing across origins
        configuration.setAllowCredentials(true);

        // Register CORS configuration for all incoming URL paths
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
