package com.classpulse.backend.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * Spring WebSocket message broker configuration.
 * <p>
 * Configures:
 * <ul>
 *   <li>STOMP over WebSocket endpoint at {@code /ws} with SockJS fallback support.</li>
 *   <li>In-memory simple broker prefixes ({@code /topic}, {@code /queue}).</li>
 *   <li>Application destination prefixes for client-to-server messages ({@code /app}).</li>
 *   <li>Inbound channel interceptors ({@link WebSocketAuthChannelInterceptor}) for frame authentication.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Inbound channel interceptor verifying client STOMP frames before routing. */
    private final WebSocketAuthChannelInterceptor authChannelInterceptor;

    /** CORS allowed origin patterns configured via properties. */
    @Value("${classpulse.cors.allowed-origins:*}")
    private String allowedOrigins = "*";

    /**
     * Registers STOMP protocol endpoints where clients connect to establish WebSocket connections.
     *
     * @param registry StompEndpointRegistry
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Parse allowed origins array from properties
        String[] patterns = (allowedOrigins == null || allowedOrigins.isBlank() || "*".equals(allowedOrigins.trim()))
                ? new String[]{"*"}
                : Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);

        // Standard pure WebSocket endpoint
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns);

        // SockJS fallback endpoint for browsers/networks restricting native WebSockets
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns)
                .withSockJS();
    }

    /**
     * Configures the message broker options: enables in-memory simple broker
     * and designates routing prefixes.
     *
     * @param registry MessageBrokerRegistry
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable built-in simple broker for pub/sub topics and point-to-point queues
        registry.enableSimpleBroker("/topic", "/queue");

        // Prefix for incoming messages routed to @MessageMapping annotated controller methods
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Configures interceptors on the inbound channel handling messages received from WebSocket clients.
     *
     * @param registration ChannelRegistration
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Register authentication interceptor to inspect CONNECT, SUBSCRIBE, and SEND frames
        registration.interceptors(authChannelInterceptor);
    }
}
