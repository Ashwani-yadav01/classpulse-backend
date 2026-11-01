package com.classpulse.backend.service;

import com.classpulse.backend.dto.JoinSessionRequest;
import com.classpulse.backend.dto.JoinSessionResponse;
import com.classpulse.backend.dto.ParticipantDto;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.ResourceNotFoundException;
import com.classpulse.backend.exception.SessionExpiredException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.model.User;
import com.classpulse.backend.model.UserRole;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;
import com.classpulse.backend.websocket.WebSocketEventPublisher;
import com.classpulse.backend.websocket.WebSocketEventType;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ParticipantService {

    private final ParticipantRepository participantRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final TeacherSecurityService teacherSecurityService;
    private final ParticipantSecurityService participantSecurityService;
    private final AuthService authService;
    private final WebSocketEventPublisher eventPublisher;
    private final Clock clock;

    @Value("${classpulse.security.require-student-auth:true}")
    private boolean requireStudentAuth = true;

    public void setRequireStudentAuth(boolean requireStudentAuth) {
        this.requireStudentAuth = requireStudentAuth;
    }

    public ParticipantService(
            ParticipantRepository participantRepository,
            SessionRepository sessionRepository,
            SessionService sessionService,
            TeacherSecurityService teacherSecurityService,
            ParticipantSecurityService participantSecurityService,
            Clock clock) {
        this(participantRepository, sessionRepository, sessionService, teacherSecurityService, participantSecurityService, null, null, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ParticipantService(
            ParticipantRepository participantRepository,
            SessionRepository sessionRepository,
            SessionService sessionService,
            TeacherSecurityService teacherSecurityService,
            ParticipantSecurityService participantSecurityService,
            AuthService authService,
            WebSocketEventPublisher eventPublisher,
            Clock clock) {
        this.participantRepository = participantRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.teacherSecurityService = teacherSecurityService;
        this.participantSecurityService = participantSecurityService;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public JoinSessionResponse joinSession(JoinSessionRequest request) {
        return joinSession(request, null);
    }

    @Transactional
    public JoinSessionResponse joinSession(JoinSessionRequest request, String authHeader) {
        String joinCode = request.getJoinCode().trim();
        String displayName = request.getDisplayName().trim();

        Session session = sessionRepository.findByJoinCodeIgnoreCase(joinCode)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with code: " + joinCode));

        Instant now = clock.instant();
        sessionService.checkAndEnforceExpiry(session);

        if (session.getStatus() == SessionStatus.EXPIRED || session.isExpired(now)) {
            throw new SessionExpiredException("Classroom session has expired");
        }

        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new BadRequestException("Classroom session is closed");
        }

        // Check if display name is taken
        Optional<Participant> existing = participantRepository.findBySessionIdAndDisplayNameIgnoreCase(session.getId(), displayName);
        if (existing.isPresent()) {
            throw new ConflictException("Display name '" + displayName + "' is already taken in this session");
        }

        // Student authentication check
        User studentUser = null;
        if (authService != null) {
            Optional<User> resolved = authService.resolveUser(authHeader);
            if (resolved.isPresent()) {
                studentUser = resolved.get();
                if (studentUser.getRole() != UserRole.STUDENT) {
                    throw new ForbiddenException("Only students can join classroom sessions as participants");
                }
            } else if (requireStudentAuth) {
                throw new UnauthorizedException("Student sign in or sign up required to join a classroom session");
            }
        }

        String rawToken = CryptoUtils.generateSecureToken(32);
        String tokenHash = CryptoUtils.hashToken(rawToken);

        Participant participant = Participant.builder()
                .id(UUID.randomUUID())
                .session(session)
                .displayName(displayName)
                .tokenHash(tokenHash)
                .isRevoked(false)
                .joinedAt(now)
                .lastSeenAt(now)
                .student(studentUser)
                .build();

        Participant saved = participantRepository.save(participant);

        // Real-time broadcast: participant joined
        if (eventPublisher != null) {
            eventPublisher.publishSessionEvent(
                    WebSocketEventType.PARTICIPANT_JOINED,
                    session.getId(),
                    Map.of(
                            "participantId", saved.getId().toString(),
                            "displayName", saved.getDisplayName(),
                            "joinedAt", saved.getJoinedAt().toString()
                    )
            );
        }

        return JoinSessionResponse.builder()
                .participantId(saved.getId())
                .sessionId(session.getId())
                .displayName(saved.getDisplayName())
                .participantToken(rawToken)
                .joinedAt(saved.getJoinedAt())
                .build();
    }

    @Transactional
    public ParticipantDto getMe(String participantToken) {
        Participant participant = participantSecurityService.authenticateParticipant(participantToken);
        return mapToDto(participant);
    }

    @Transactional
    public List<ParticipantDto> getSessionParticipants(UUID sessionId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        return participantRepository.findBySessionIdAndIsRevokedFalse(sessionId)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @Transactional
    public void revokeParticipant(UUID sessionId, UUID participantId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Participant participant = participantRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found with ID: " + participantId));

        if (!participant.getSession().getId().equals(sessionId)) {
            throw new BadRequestException("Participant does not belong to session: " + sessionId);
        }

        participant.setRevoked(true);
        participantRepository.save(participant);

        // Real-time broadcast: participant revoked
        if (eventPublisher != null) {
            eventPublisher.publishSessionEvent(
                    WebSocketEventType.PARTICIPANT_REVOKED,
                    session.getId(),
                    Map.of(
                            "participantId", participant.getId().toString(),
                            "displayName", participant.getDisplayName()
                    )
            );
        }
    }

    private ParticipantDto mapToDto(Participant participant) {
        return ParticipantDto.builder()
                .id(participant.getId())
                .displayName(participant.getDisplayName())
                .joinedAt(participant.getJoinedAt())
                .lastSeenAt(participant.getLastSeenAt())
                .build();
    }
}
