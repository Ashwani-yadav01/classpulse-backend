package com.classpulse.backend.service;

import com.classpulse.backend.dto.JoinSessionRequest;
import com.classpulse.backend.dto.JoinSessionResponse;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.ResourceNotFoundException;
import com.classpulse.backend.exception.SessionExpiredException;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParticipantServiceTest {

    @Mock
    private ParticipantRepository participantRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionService sessionService;

    private TeacherSecurityService teacherSecurityService;
    private ParticipantSecurityService participantSecurityService;
    private Clock fixedClock;
    private ParticipantService participantService;

    private final Instant now = Instant.parse("2026-10-25T12:00:00Z");

    @BeforeEach
    void setUp() {
        teacherSecurityService = new TeacherSecurityService("master-key");
        fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        participantSecurityService = new ParticipantSecurityService(participantRepository, fixedClock);
        participantService = new ParticipantService(
                participantRepository,
                sessionRepository,
                sessionService,
                teacherSecurityService,
                participantSecurityService,
                fixedClock
        );
    }

    @Test
    void joinSession_shouldSucceedWithSecureToken() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId)
                .title("History")
                .joinCode("HIST12")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash("hash")
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        when(sessionRepository.findByJoinCodeIgnoreCase("HIST12")).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndDisplayNameIgnoreCase(sessionId, "Bob")).thenReturn(Optional.empty());
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JoinSessionRequest request = JoinSessionRequest.builder()
                .joinCode("HIST12")
                .displayName("Bob")
                .build();

        JoinSessionResponse response = participantService.joinSession(request);

        assertThat(response.getParticipantId()).isNotNull();
        assertThat(response.getSessionId()).isEqualTo(sessionId);
        assertThat(response.getDisplayName()).isEqualTo("Bob");
        assertThat(response.getParticipantToken()).isNotBlank();
    }

    @Test
    void joinSession_whenInvalidJoinCode_shouldThrowNotFound() {
        when(sessionRepository.findByJoinCodeIgnoreCase("INVALID")).thenReturn(Optional.empty());

        JoinSessionRequest request = JoinSessionRequest.builder()
                .joinCode("INVALID")
                .displayName("Bob")
                .build();

        assertThatThrownBy(() -> participantService.joinSession(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found with code");
    }

    @Test
    void joinSession_whenDisplayNameAlreadyTaken_shouldThrowConflict() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId)
                .title("History")
                .joinCode("HIST12")
                .status(SessionStatus.ACTIVE)
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        when(sessionRepository.findByJoinCodeIgnoreCase("HIST12")).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionIdAndDisplayNameIgnoreCase(sessionId, "Bob"))
                .thenReturn(Optional.of(Participant.builder().id(UUID.randomUUID()).displayName("Bob").build()));

        JoinSessionRequest request = JoinSessionRequest.builder()
                .joinCode("HIST12")
                .displayName("Bob")
                .build();

        assertThatThrownBy(() -> participantService.joinSession(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    void joinSession_whenSessionClosed_shouldThrowBadRequest() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId)
                .title("History")
                .joinCode("HIST12")
                .status(SessionStatus.CLOSED)
                .createdAt(now.minus(Duration.ofHours(2)))
                .expiresAt(now.plus(Duration.ofHours(22)))
                .build();

        when(sessionRepository.findByJoinCodeIgnoreCase("HIST12")).thenReturn(Optional.of(session));

        JoinSessionRequest request = JoinSessionRequest.builder()
                .joinCode("HIST12")
                .displayName("Bob")
                .build();

        assertThatThrownBy(() -> participantService.joinSession(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void joinSession_whenSessionExpired_shouldThrowSessionExpiredException() {
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .title("History")
                .joinCode("HIST12")
                .status(SessionStatus.EXPIRED)
                .createdAt(now.minus(Duration.ofHours(30)))
                .expiresAt(now.minus(Duration.ofHours(6)))
                .build();

        when(sessionRepository.findByJoinCodeIgnoreCase("HIST12")).thenReturn(Optional.of(session));

        JoinSessionRequest request = JoinSessionRequest.builder()
                .joinCode("HIST12")
                .displayName("Bob")
                .build();

        assertThatThrownBy(() -> participantService.joinSession(request))
                .isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void getMe_whenParticipantRevoked_shouldThrowForbidden() {
        String rawToken = "sample-token-123";
        String tokenHash = CryptoUtils.hashToken(rawToken);

        Participant participant = Participant.builder()
                .id(UUID.randomUUID())
                .displayName("Charlie")
                .tokenHash(tokenHash)
                .isRevoked(true)
                .build();

        when(participantRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> participantService.getMe(rawToken))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void revokeParticipant_shouldSetRevokedTrue() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        Session session = Session.builder()
                .id(sessionId)
                .title("Class")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(CryptoUtils.hashToken("teacher-token"))
                .build();

        Participant participant = Participant.builder()
                .id(participantId)
                .session(session)
                .displayName("DisruptiveUser")
                .isRevoked(false)
                .build();

        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(participantRepository.findById(participantId)).thenReturn(Optional.of(participant));

        participantService.revokeParticipant(sessionId, participantId, "teacher-token", null);

        assertThat(participant.isRevoked()).isTrue();
        verify(participantRepository).save(participant);
    }
}
