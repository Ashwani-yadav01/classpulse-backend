package com.classpulse.backend.websocket;

import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.UserRole;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.JwtClaims;
import com.classpulse.backend.security.JwtTokenService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring messaging {@link ChannelInterceptor} enforcing strict authentication and authorization
 * on all incoming STOMP protocol frames (CONNECT, SUBSCRIBE, SEND).
 * <p>
 * Security controls enforced:
 * <ul>
 *   <li>CONNECT: Validates client credentials (Master Key, JWT, Session Teacher Token, or Participant Token)
 *       and establishes a persistent {@link WebSocketUserPrincipal}.</li>
 *   <li>SUBSCRIBE: Enforces topic isolation so participants cannot view teacher topics or other sessions' data.</li>
 *   <li>SEND: Prevents clients from publishing directly into broker-managed topics (bypassing backend logic).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    /** Service verifying master keys and teacher privileges. */
    private final TeacherSecurityService teacherSecurityService;

    /** Session repository for resolving teacher token hashes and verifying session ownership. */
    private final SessionRepository sessionRepository;

    /** Participant repository for token-based student lookups and revocation checks. */
    private final ParticipantRepository participantRepository;

    /** JWT service for parsing and verifying bearer credentials. */
    private final JwtTokenService jwtTokenService;

    /** Key used to store authenticated principal in the WebSocket session attribute map. */
    private static final String USER_PRINCIPAL_KEY = "WS_USER_PRINCIPAL";

    /** Regex pattern matching valid session-scoped topic destinations: /topic/sessions/{uuid}[/...] */
    private static final Pattern SESSION_TOPIC_PATTERN = Pattern.compile("^/topic/sessions/([0-9a-fA-F-]+)(/.*)?$");

    /**
     * Intercepts messages before they are processed by the inbound channel.
     * Evaluates frame type (CONNECT, SUBSCRIBE, SEND) and delegates to specific security validators.
     *
     * @param message incoming Spring messaging Message
     * @param channel target MessageChannel
     * @return original or modified Message, or throws AccessDeniedException
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Step 1: Wrap message with StompHeaderAccessor to read and mutate STOMP headers
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !accessor.isMutable()) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        // Step 2: Restore cached principal from session attributes if not already bound
        if (accessor.getUser() == null && accessor.getSessionAttributes() != null) {
            Principal cached = (Principal) accessor.getSessionAttributes().get(USER_PRINCIPAL_KEY);
            if (cached != null) {
                accessor.setUser(cached);
            }
        }

        // Step 3: Route validation based on the STOMP command
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            // Authenticate initial handshake connection frame
            authenticateConnect(accessor);
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            // Authorize destination topic subscription
            authorizeSubscription(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            // Guard destination send requests
            authorizeSend(accessor);
        }

        return message;
    }

    /**
     * Authenticates an incoming STOMP CONNECT frame by inspecting native headers.
     * Inspects credentials in priority: Master Key -> JWT -> Teacher Token -> Participant Token.
     *
     * @param accessor mutable STOMP header accessor
     * @throws AccessDeniedException if credentials are absent or fail cryptographic validation
     */
    private void authenticateConnect(StompHeaderAccessor accessor) {
        // Extract native connection headers
        String teacherKey = accessor.getFirstNativeHeader("X-Teacher-Key");
        String teacherToken = accessor.getFirstNativeHeader("X-Teacher-Token");
        String participantToken = accessor.getFirstNativeHeader("X-Participant-Token");
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = accessor.getFirstNativeHeader("X-Auth-Token");
        }

        WebSocketUserPrincipal principal = null;

        // Channel 1: Master Teacher Key verification
        if (teacherKey != null && !teacherKey.isBlank()) {
            if (!teacherSecurityService.isMasterKeyValid(teacherKey)) {
                throw new AccessDeniedException("Invalid teacher master key");
            }
            principal = new WebSocketUserPrincipal("MASTER_TEACHER", null, WebSocketUserPrincipal.Role.MASTER_TEACHER, null);
        }

        // Channel 2: JWT Bearer Token (Registered Teacher or Student account)
        if (principal == null && authHeader != null && !authHeader.isBlank()) {
            Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(authHeader);
            if (claimsOpt.isPresent()) {
                JwtClaims claims = claimsOpt.get();
                if (claims.getRole() == UserRole.TEACHER) {
                    principal = new WebSocketUserPrincipal(
                            "TEACHER_" + claims.getUserId(),
                            null,
                            WebSocketUserPrincipal.Role.TEACHER,
                            null,
                            claims.getUserId()
                    );
                } else {
                    principal = new WebSocketUserPrincipal(
                            "STUDENT_" + claims.getUserId(),
                            null,
                            WebSocketUserPrincipal.Role.PARTICIPANT,
                            null,
                            claims.getUserId()
                    );
                }
            }
        }

        // Channel 3: Per-Session Teacher Token (or JWT passed in X-Teacher-Token header)
        if (principal == null && teacherToken != null && !teacherToken.isBlank()) {
            // Check if client passed a JWT in X-Teacher-Token
            Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(teacherToken);
            if (claimsOpt.isPresent() && claimsOpt.get().getRole() == UserRole.TEACHER) {
                principal = new WebSocketUserPrincipal(
                        "TEACHER_" + claimsOpt.get().getUserId(),
                        null,
                        WebSocketUserPrincipal.Role.TEACHER,
                        null,
                        claimsOpt.get().getUserId()
                );
            } else {
                // Otherwise treat as opaque session token: hash and locate session
                String tokenHash = CryptoUtils.hashToken(teacherToken);
                Optional<Session> sessionOpt = sessionRepository.findByTeacherTokenHash(tokenHash);
                if (sessionOpt.isEmpty()) {
                    throw new AccessDeniedException("Invalid teacher token");
                }
                Session session = sessionOpt.get();
                principal = new WebSocketUserPrincipal("TEACHER_" + session.getId(), session.getId(), WebSocketUserPrincipal.Role.TEACHER, null);
            }
        }

        // Channel 4: Per-Session Participant Token (Student)
        if (principal == null && participantToken != null && !participantToken.isBlank()) {
            String tokenHash = CryptoUtils.hashToken(participantToken);
            Optional<Participant> participantOpt = participantRepository.findByTokenHash(tokenHash);
            if (participantOpt.isEmpty()) {
                throw new AccessDeniedException("Invalid participant token");
            }
            Participant participant = participantOpt.get();
            if (participant.isRevoked()) {
                throw new AccessDeniedException("Participant token has been revoked");
            }
            principal = new WebSocketUserPrincipal(
                    "PARTICIPANT_" + participant.getId(),
                    participant.getSession().getId(),
                    WebSocketUserPrincipal.Role.PARTICIPANT,
                    participant.getId()
            );
        }

        // Reject if no valid credential channel succeeded
        if (principal == null) {
            throw new AccessDeniedException("Authentication credentials required for WebSocket connection");
        }

        // Store principal in session attributes so subsequent frames on this socket retain authentication
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(USER_PRINCIPAL_KEY, principal);
        }
        accessor.setUser(principal);
    }

    /**
     * Authorizes a client's SUBSCRIBE frame.
     * Prevents cross-session leaks, unauthorized destination listening, and non-teachers from subscribing to /teacher topics.
     *
     * @param accessor STOMP header accessor
     * @throws AccessDeniedException if subscription target is unauthorized
     */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        // Step 1: Ensure principal is available
        Principal principal = accessor.getUser();
        if (principal == null && accessor.getSessionAttributes() != null) {
            principal = (Principal) accessor.getSessionAttributes().get(USER_PRINCIPAL_KEY);
            if (principal != null) {
                accessor.setUser(principal);
            }
        }

        if (!(principal instanceof WebSocketUserPrincipal user)) {
            throw new AccessDeniedException("Unauthenticated subscription request");
        }

        // Step 2: Validate destination presence
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Destination cannot be empty");
        }

        // Step 3: Match destination against allowed session topic pattern
        Matcher matcher = SESSION_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            throw new AccessDeniedException("Invalid subscription destination: " + destination);
        }

        // Step 4: Extract target session UUID
        UUID targetSessionId;
        try {
            targetSessionId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Invalid session UUID in destination: " + destination);
        }

        // Step 5: Master teacher has unrestricted access
        if (user.isMasterTeacher()) {
            return;
        }

        // Step 6: Teacher subscription rules
        if (user.isTeacher()) {
            // Guard against session mismatch if principal is scoped to a specific session
            if (user.getSessionId() != null && !targetSessionId.equals(user.getSessionId())) {
                throw new AccessDeniedException("Cross-session subscription forbidden: user belongs to " + user.getSessionId() + " but attempted to subscribe to " + targetSessionId);
            }
            // If authenticated as a general user account, verify teacher owns the session
            if (user.getUserId() != null) {
                Optional<Session> sessOpt = sessionRepository.findById(targetSessionId);
                if (sessOpt.isPresent()) {
                    Session sess = sessOpt.get();
                    if (sess.getTeacher() != null && !sess.getTeacher().getId().equals(user.getUserId())) {
                        throw new AccessDeniedException("Teacher does not own session " + targetSessionId);
                    }
                }
            }
            return;
        }

        // Step 7: Participant subscription rules
        // Forbid participants from listening to teacher-only channels
        if (destination.contains("/teacher")) {
            throw new AccessDeniedException("Participants are not permitted to subscribe to teacher-only destination: " + destination);
        }

        // Forbid participants from subscribing to any session other than their enrolled session
        if (user.getSessionId() != null && !targetSessionId.equals(user.getSessionId())) {
            throw new AccessDeniedException("Cross-session subscription forbidden: user belongs to " + user.getSessionId() + " but attempted to subscribe to " + targetSessionId);
        }
    }

    /**
     * Authorizes a client's SEND frame.
     * Prevents clients from sending messages directly to broadcast topics (e.g. forging events).
     *
     * @param accessor STOMP header accessor
     * @throws AccessDeniedException if client sends directly to /topic destinations
     */
    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        // Guard against client publishing directly into public pub/sub topics
        if (destination != null && destination.startsWith("/topic/")) {
            throw new AccessDeniedException("Clients are not allowed to send directly to broker destinations");
        }
    }
}
