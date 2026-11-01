package com.classpulse.backend.service;

import com.classpulse.backend.dto.CreateSessionRequest;
import com.classpulse.backend.dto.SessionResponse;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;
import com.classpulse.backend.websocket.WebSocketEventPublisher;
import com.classpulse.backend.websocket.WebSocketEventType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private WebSocketEventPublisher eventPublisher;

    private TeacherSecurityService teacherSecurityService;
    private QrCodeService qrCodeService;
    private Clock fixedClock;
    private SessionService sessionService;

    private final Instant now = Instant.parse("2026-10-25T10:00:00Z");

    @BeforeEach
    void setUp() {
        teacherSecurityService = new TeacherSecurityService("master-secret");
        qrCodeService = new QrCodeService("http://localhost:3000/join");
        fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        sessionService = new SessionService(sessionRepository, teacherSecurityService, qrCodeService, fixedClock, eventPublisher);
    }

    @Test
    void createSession_shouldCreateWith24HourExpiryAndTeacherToken() {
        when(sessionRepository.existsByJoinCodeIgnoreCase(any())).thenReturn(false);
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateSessionRequest request = CreateSessionRequest.builder()
                .title("Physics 101")
                .build();

        SessionResponse response = sessionService.createSession(request, "master-secret");

        assertThat(response.getId()).isNotNull();
        assertThat(response.getTitle()).isEqualTo("Physics 101");
        assertThat(response.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(response.getTeacherToken()).isNotBlank();
        assertThat(response.getCreatedAt()).isEqualTo(now);
        assertThat(response.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(response.getJoinUrl()).contains("code=");
    }

    @Test
    void createSession_withInvalidTeacherKey_shouldThrowUnauthorized() {
        CreateSessionRequest request = CreateSessionRequest.builder()
                .title("Unauthorized Class")
                .build();

        assertThatThrownBy(() -> sessionService.createSession(request, "wrong-key"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getSessionById_withValidTeacherToken_shouldSucceed() {
        when(sessionRepository.existsByJoinCodeIgnoreCase(any())).thenReturn(false);
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SessionResponse created = sessionService.createSession(CreateSessionRequest.builder().title("Chemistry").build(), "master-secret");

        Session session = Session.builder()
                .id(created.getId())
                .title("Chemistry")
                .joinCode(created.getJoinCode())
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(CryptoUtils.hashToken(created.getTeacherToken()))
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        when(sessionRepository.findById(created.getId())).thenReturn(Optional.of(session));

        SessionResponse fetched = sessionService.getSessionById(created.getId(), created.getTeacherToken(), null);
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getTitle()).isEqualTo("Chemistry");
    }

    @Test
    void getSessionById_withOwnershipViolation_teacherTokenFromAnotherSession_shouldThrowForbidden() {
        UUID sessionAId = UUID.randomUUID();
        UUID sessionBId = UUID.randomUUID();

        Session sessionB = Session.builder()
                .id(sessionBId)
                .title("Session B")
                .joinCode("SESSB2")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(CryptoUtils.hashToken("teacher-token-for-session-b"))
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        when(sessionRepository.findById(sessionBId)).thenReturn(Optional.of(sessionB));

        // Teacher from Session A attempts to access Session B with Session A's token
        assertThatThrownBy(() -> sessionService.getSessionById(sessionBId, "teacher-token-for-session-a", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Invalid teacher credentials");
    }

    @Test
    void getSessionById_withoutTeacherToken_shouldThrowUnauthorized() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId)
                .title("Biology")
                .joinCode("BIO123")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(CryptoUtils.hashToken("correct-token"))
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.getSessionById(sessionId, null, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void checkAndEnforceExpiry_whenPastExpiry_shouldMarkExpired() {
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .title("Math")
                .status(SessionStatus.ACTIVE)
                .createdAt(now.minus(Duration.ofHours(26)))
                .expiresAt(now.minus(Duration.ofHours(2)))
                .build();

        sessionService.checkAndEnforceExpiry(session);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXPIRED);
        verify(sessionRepository).save(session);
    }

    @Test
    void closeSession_shouldUpdateStatusAndPublishWebSocketEvent() {
        UUID sessionId = UUID.randomUUID();
        String token = "valid-token";
        Session session = Session.builder()
                .id(sessionId)
                .title("History")
                .joinCode("HIST01")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(CryptoUtils.hashToken(token))
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        SessionResponse response = sessionService.closeSession(sessionId, token, null);

        assertThat(response.getStatus()).isEqualTo(SessionStatus.CLOSED);
        verify(sessionRepository).save(session);
        verify(eventPublisher).publishSessionEvent(eq(WebSocketEventType.SESSION_CLOSED), eq(sessionId), any());
    }
}
