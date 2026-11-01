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

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final TeacherSecurityService teacherSecurityService;
    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final JwtTokenService jwtTokenService;

    private static final String USER_PRINCIPAL_KEY = "WS_USER_PRINCIPAL";
    private static final Pattern SESSION_TOPIC_PATTERN = Pattern.compile("^/topic/sessions/([0-9a-fA-F-]+)(/.*)?$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !accessor.isMutable()) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        // Restore principal from session attributes if not directly set
        if (accessor.getUser() == null && accessor.getSessionAttributes() != null) {
            Principal cached = (Principal) accessor.getSessionAttributes().get(USER_PRINCIPAL_KEY);
            if (cached != null) {
                accessor.setUser(cached);
            }
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscription(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            authorizeSend(accessor);
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String teacherKey = accessor.getFirstNativeHeader("X-Teacher-Key");
        String teacherToken = accessor.getFirstNativeHeader("X-Teacher-Token");
        String participantToken = accessor.getFirstNativeHeader("X-Participant-Token");
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = accessor.getFirstNativeHeader("X-Auth-Token");
        }

        WebSocketUserPrincipal principal = null;

        // 1. Master Teacher Key
        if (teacherKey != null && !teacherKey.isBlank()) {
            if (!teacherSecurityService.isMasterKeyValid(teacherKey)) {
                throw new AccessDeniedException("Invalid teacher master key");
            }
            principal = new WebSocketUserPrincipal("MASTER_TEACHER", null, WebSocketUserPrincipal.Role.MASTER_TEACHER, null);
        }

        // 2. JWT Bearer Token (Teacher or Student)
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

        // 3. Per-Session Teacher Token
        if (principal == null && teacherToken != null && !teacherToken.isBlank()) {
            // Check if it's actually a JWT passed in X-Teacher-Token
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
                String tokenHash = CryptoUtils.hashToken(teacherToken);
                Optional<Session> sessionOpt = sessionRepository.findByTeacherTokenHash(tokenHash);
                if (sessionOpt.isEmpty()) {
                    throw new AccessDeniedException("Invalid teacher token");
                }
                Session session = sessionOpt.get();
                principal = new WebSocketUserPrincipal("TEACHER_" + session.getId(), session.getId(), WebSocketUserPrincipal.Role.TEACHER, null);
            }
        }

        // 4. Per-Session Participant Token
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

        if (principal == null) {
            throw new AccessDeniedException("Authentication credentials required for WebSocket connection");
        }

        // Persist principal into session attributes so subsequent STOMP frames (SUBSCRIBE, SEND) retain it
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(USER_PRINCIPAL_KEY, principal);
        }
        accessor.setUser(principal);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
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

        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Destination cannot be empty");
        }

        Matcher matcher = SESSION_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            // Only allow subscription to registered session topics
            throw new AccessDeniedException("Invalid subscription destination: " + destination);
        }

        UUID targetSessionId;
        try {
            targetSessionId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Invalid session UUID in destination: " + destination);
        }

        // Master teacher can access any session
        if (user.isMasterTeacher()) {
            return;
        }

        // Teacher subscription check
        if (user.isTeacher()) {
            if (user.getSessionId() != null && !targetSessionId.equals(user.getSessionId())) {
                throw new AccessDeniedException("Cross-session subscription forbidden: user belongs to " + user.getSessionId() + " but attempted to subscribe to " + targetSessionId);
            }
            // If authenticated as a general teacher (JWT), check ownership if user.getUserId() is set
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

        // Participant check
        if (destination.contains("/teacher")) {
            throw new AccessDeniedException("Participants are not permitted to subscribe to teacher-only destination: " + destination);
        }

        if (user.getSessionId() != null && !targetSessionId.equals(user.getSessionId())) {
            throw new AccessDeniedException("Cross-session subscription forbidden: user belongs to " + user.getSessionId() + " but attempted to subscribe to " + targetSessionId);
        }
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith("/topic/")) {
            throw new AccessDeniedException("Clients are not allowed to send directly to broker destinations");
        }
    }
}
