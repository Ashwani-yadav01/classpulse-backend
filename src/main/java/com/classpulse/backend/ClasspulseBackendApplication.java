package com.classpulse.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ClassPulse Backend Application.
 * <p>
 * ClassPulse is a real-time classroom engagement and live polling platform designed
 * for teachers and students. This Spring Boot application provides:
 * <ul>
 *   <li>REST APIs for authentication, session orchestration, participants, activities, and responses.</li>
 *   <li>WebSocket STOMP endpoints for real-time live synchronization (activities, responses, leaderboards).</li>
 *   <li>Flyway database migrations for automated relational schema evolution.</li>
 *   <li>Stateless JWT-based security for teachers and session-scoped cryptographic tokens for participants.</li>
 * </ul>
 *
 * Annotations:
 * - {@link SpringBootApplication}: Meta-annotation enabling:
 *     1. Auto-configuration ({@code @EnableAutoConfiguration})
 *     2. Component scanning ({@code @ComponentScan}) across the base package and subpackages
 *     3. Configuration class declaration ({@code @SpringBootConfiguration})
 */
@SpringBootApplication
public class ClasspulseBackendApplication {

	/**
	 * Main execution method that bootstraps and launches the Spring Boot application context.
	 *
	 * @param args Command-line arguments passed to the JVM on launch
	 */
	public static void main(String[] args) {
		// Launch the Spring application context using SpringApplication.run()
		// This initializes the embedded web server (Tomcat/Netty), runs Flyway migrations,
		// sets up the Spring Bean dependency injection container, and configures WebSocket message brokers.
		SpringApplication.run(ClasspulseBackendApplication.class, args);
	}

}
