package com.classpulse.backend.websocket.security;

import com.classpulse.backend.common.CryptoUtils;
import com.classpulse.backend.participant.Participant;
import com.classpulse.backend.participant.ParticipantRepository;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.session.Session;
import com.classpulse.backend.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthChannelInterceptorTest {

    @Mock
    private TeacherSecurityService teacherSecurityService;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private ParticipantRepository participantRepository;

    @Mock
    private MessageChannel messageChannel;

    private WebSocketAuthChannelInterceptor interceptor;

    private UUID sessionId;
    private final String teacherToken = "teacher-secret-token";
    private final String participantToken = "participant-secret-token";

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthChannelInterceptor(teacherSecurityService, sessionRepository, participantRepository);
        sessionId = UUID.randomUUID();
    }

    @Test
    void connect_withValidTeacherToken_shouldAuthenticate() {
        Session session = Session.builder().id(sessionId).teacherTokenHash(CryptoUtils.hashToken(teacherToken)).build();
        when(sessionRepository.findByTeacherTokenHash(CryptoUtils.hashToken(teacherToken))).thenReturn(Optional.of(session));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader("X-Teacher-Token", teacherToken);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, messageChannel);
        StompHeaderAccessor resAccessor = StompHeaderAccessor.wrap(result);

        assertThat(resAccessor.getUser()).isNotNull();
        WebSocketUserPrincipal principal = (WebSocketUserPrincipal) resAccessor.getUser();
        assertThat(principal.isTeacher()).isTrue();
        assertThat(principal.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void connect_withValidParticipantToken_shouldAuthenticate() {
        Session session = Session.builder().id(sessionId).build();
        Participant participant = Participant.builder()
                .id(UUID.randomUUID())
                .session(session)
                .isRevoked(false)
                .build();

        when(participantRepository.findByTokenHash(CryptoUtils.hashToken(participantToken))).thenReturn(Optional.of(participant));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader("X-Participant-Token", participantToken);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, messageChannel);
        StompHeaderAccessor resAccessor = StompHeaderAccessor.wrap(result);

        assertThat(resAccessor.getUser()).isNotNull();
        WebSocketUserPrincipal principal = (WebSocketUserPrincipal) resAccessor.getUser();
        assertThat(principal.isTeacher()).isFalse();
        assertThat(principal.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void connect_withRevokedParticipantToken_shouldThrowAccessDenied() {
        Session session = Session.builder().id(sessionId).build();
        Participant participant = Participant.builder()
                .id(UUID.randomUUID())
                .session(session)
                .isRevoked(true)
                .build();

        when(participantRepository.findByTokenHash(CryptoUtils.hashToken(participantToken))).thenReturn(Optional.of(participant));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("X-Participant-Token", participantToken);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, messageChannel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void connect_withoutCredentials_shouldThrowAccessDenied() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, messageChannel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authentication credentials required");
    }

    @Test
    void subscribe_crossSessionDestination_shouldThrowAccessDenied() {
        UUID otherSessionId = UUID.randomUUID();
        WebSocketUserPrincipal participant = new WebSocketUserPrincipal("PARTICIPANT_1", sessionId, WebSocketUserPrincipal.Role.PARTICIPANT, UUID.randomUUID());

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(participant);
        accessor.setDestination("/topic/sessions/" + otherSessionId + "/activities/" + UUID.randomUUID());
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, messageChannel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Cross-session subscription forbidden");
    }

    @Test
    void subscribe_participantToTeacherTopic_shouldThrowAccessDenied() {
        WebSocketUserPrincipal participant = new WebSocketUserPrincipal("PARTICIPANT_1", sessionId, WebSocketUserPrincipal.Role.PARTICIPANT, UUID.randomUUID());

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(participant);
        accessor.setDestination("/topic/sessions/" + sessionId + "/teacher");
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, messageChannel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Participants are not permitted to subscribe to teacher-only destination");
    }

    @Test
    void send_directlyToBrokerTopic_shouldThrowAccessDenied() {
        WebSocketUserPrincipal participant = new WebSocketUserPrincipal("PARTICIPANT_1", sessionId, WebSocketUserPrincipal.Role.PARTICIPANT, UUID.randomUUID());

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setUser(participant);
        accessor.setDestination("/topic/sessions/" + sessionId);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, messageChannel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Clients are not allowed to send directly to broker destinations");
    }
}
