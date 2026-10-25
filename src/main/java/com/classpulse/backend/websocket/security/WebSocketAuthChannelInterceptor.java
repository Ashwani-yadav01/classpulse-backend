package com.classpulse.backend.websocket.security;

import com.classpulse.backend.common.CryptoUtils;
import com.classpulse.backend.common.exception.UnauthorizedException;
import com.classpulse.backend.participant.Participant;
import com.classpulse.backend.participant.ParticipantRepository;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.session.Session;
import com.classpulse.backend.session.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
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

    private static final Pattern SESSION_TOPIC_PATTERN = Pattern.compile("^/topic/sessions/([0-9a-fA-F-]+)(/.*)?$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !accessor.isMutable()) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
            return org.springframework.messaging.support.MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
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

        if (teacherKey != null && !teacherKey.isBlank()) {
            if (!teacherSecurityService.isMasterKeyValid(teacherKey)) {
                throw new AccessDeniedException("Invalid teacher master key");
            }
            accessor.setUser(new WebSocketUserPrincipal("MASTER_TEACHER", null, WebSocketUserPrincipal.Role.MASTER_TEACHER, null));
            return;
        }

        if (teacherToken != null && !teacherToken.isBlank()) {
            String tokenHash = CryptoUtils.hashToken(teacherToken);
            Optional<Session> sessionOpt = sessionRepository.findByTeacherTokenHash(tokenHash);
            if (sessionOpt.isEmpty()) {
                throw new AccessDeniedException("Invalid teacher token");
            }
            Session session = sessionOpt.get();
            accessor.setUser(new WebSocketUserPrincipal("TEACHER_" + session.getId(), session.getId(), WebSocketUserPrincipal.Role.TEACHER, null));
            return;
        }

        if (participantToken != null && !participantToken.isBlank()) {
            String tokenHash = CryptoUtils.hashToken(participantToken);
            Optional<Participant> participantOpt = participantRepository.findByTokenHash(tokenHash);
            if (participantOpt.isEmpty()) {
                throw new AccessDeniedException("Invalid participant token");
            }
            Participant participant = participantOpt.get();
            if (participant.isRevoked()) {
                throw new AccessDeniedException("Participant token has been revoked");
            }
            accessor.setUser(new WebSocketUserPrincipal(
                    "PARTICIPANT_" + participant.getId(),
                    participant.getSession().getId(),
                    WebSocketUserPrincipal.Role.PARTICIPANT,
                    participant.getId()
            ));
            return;
        }

        throw new AccessDeniedException("Authentication credentials required for WebSocket connection");
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
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

        // Enforce session boundary
        if (!targetSessionId.equals(user.getSessionId())) {
            throw new AccessDeniedException("Cross-session subscription forbidden: user belongs to " + user.getSessionId() + " but attempted to subscribe to " + targetSessionId);
        }

        // Check if destination is teacher-only
        if (destination.contains("/teacher") && !user.isTeacher()) {
            throw new AccessDeniedException("Participants are not permitted to subscribe to teacher-only destination: " + destination);
        }
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith("/topic/")) {
            throw new AccessDeniedException("Clients are not allowed to send directly to broker destinations");
        }
    }
}
