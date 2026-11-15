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

/**
 * Service managing student enrollment, authentication tokens, participant rosters,
 * and teacher moderation controls (revocation/kicking).
 * <p>
 * Key invariants:
 * <ul>
 *   <li>Display names must be unique per session (case-insensitive).</li>
 *   <li>Only active sessions accept student joins; expired or closed sessions reject requests.</li>
 *   <li>Students receive an unguessable 32-byte cryptographically secure token; only its SHA-256 hash is saved.</li>
 *   <li>When students join or are revoked, real-time WebSocket notifications are broadcast to classroom channels.</li>
 * </ul>
 */
@Service
public class ParticipantService {

    /** Repository managing persistent Participant entities. */
    private final ParticipantRepository participantRepository;

    /** Repository managing persistent Session entities. */
    private final SessionRepository sessionRepository;

    /** Service managing session lifecycle and expiration verification. */
    private final SessionService sessionService;

    /** Security service for verifying teacher authorizations. */
    private final TeacherSecurityService teacherSecurityService;

    /** Security service for participant token authentication and heartbeats. */
    private final ParticipantSecurityService participantSecurityService;

    /** Authentication service for resolving registered student accounts. */
    private final AuthService authService;

    /** Real-time WebSocket event publisher. */
    private final WebSocketEventPublisher eventPublisher;

    /** System clock provider for deterministic timestamp generation. */
    private final Clock clock;

    /** Policy flag controlling whether registered student account login is required to join. */
    @Value("${classpulse.security.require-student-auth:true}")
    private boolean requireStudentAuth = true;

    /**
     * Mutator allowing test suites to toggle the student login requirement.
     *
     * @param requireStudentAuth true to mandate registered student accounts; false for anonymous nicknames
     */
    public void setRequireStudentAuth(boolean requireStudentAuth) {
        this.requireStudentAuth = requireStudentAuth;
    }

    /**
     * Constructor for unit tests operating without full event publisher and auth service injection.
     *
     * @param participantRepository      participant repo
     * @param sessionRepository          session repo
     * @param sessionService             session service
     * @param teacherSecurityService     teacher security service
     * @param participantSecurityService participant security service
     * @param clock                      application clock
     */
    public ParticipantService(
            ParticipantRepository participantRepository,
            SessionRepository sessionRepository,
            SessionService sessionService,
            TeacherSecurityService teacherSecurityService,
            ParticipantSecurityService participantSecurityService,
            Clock clock) {
        this(participantRepository, sessionRepository, sessionService, teacherSecurityService, participantSecurityService, null, null, clock);
    }

    /**
     * Full dependency-injected constructor wired by Spring.
     *
     * @param participantRepository      participant repo
     * @param sessionRepository          session repo
     * @param sessionService             session service
     * @param teacherSecurityService     teacher security service
     * @param participantSecurityService participant security service
     * @param authService                auth service
     * @param eventPublisher             WebSocket publisher
     * @param clock                      application clock
     */
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

    /**
     * Enrolls a student into a classroom session without user account authentication.
     *
     * @param request the join request payload
     * @return join response containing participant ID and secret token
     */
    @Transactional
    public JoinSessionResponse joinSession(JoinSessionRequest request) {
        return joinSession(request, null);
    }

    /**
     * Enrolls a student into a classroom session, validating session state, nickname availability,
     * and optional/enforced registered student authentication.
     *
     * @param request    the join request payload containing join code and display name
     * @param authHeader optional JWT Authorization header identifying a logged-in user
     * @return {@link JoinSessionResponse} containing participant credentials
     * @throws ResourceNotFoundException if session join code does not match any known session
     * @throws SessionExpiredException   if session lifespan has passed
     * @throws BadRequestException       if session is closed
     * @throws ConflictException         if display nickname is already taken in this session
     * @throws UnauthorizedException     if student authentication is required but missing
     * @throws ForbiddenException        if an account with TEACHER role attempts to join as student
     */
    @Transactional
    public JoinSessionResponse joinSession(JoinSessionRequest request, String authHeader) {
        // Step 1: Clean inputs by trimming surrounding whitespace
        String joinCode = request.getJoinCode().trim();
        String displayName = request.getDisplayName().trim();

        // Step 2: Look up session by case-insensitive join code; fail with 404 if not found
        Session session = sessionRepository.findByJoinCodeIgnoreCase(joinCode)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with code: " + joinCode));

        // Step 3: Check and enforce temporal expiration on the session
        Instant now = clock.instant();
        sessionService.checkAndEnforceExpiry(session);

        // Step 4: Validate that session is currently open and has not expired
        if (session.getStatus() == SessionStatus.EXPIRED || session.isExpired(now)) {
            throw new SessionExpiredException("Classroom session has expired");
        }

        // Step 5: Validate that session is not explicitly closed
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new BadRequestException("Classroom session is closed");
        }

        // Step 6: Verify display name uniqueness within this session (case-insensitive check)
        Optional<Participant> existing = participantRepository.findBySessionIdAndDisplayNameIgnoreCase(session.getId(), displayName);
        if (existing.isPresent()) {
            throw new ConflictException("Display name '" + displayName + "' is already taken in this session");
        }

        // Step 7: Handle student account identity validation
        User studentUser = null;
        if (authService != null) {
            // Attempt to resolve registered user account from authorization header
            Optional<User> resolved = authService.resolveUser(authHeader);
            if (resolved.isPresent()) {
                studentUser = resolved.get();
                // Ensure accounts with TEACHER role cannot join sessions as student participants
                if (studentUser.getRole() != UserRole.STUDENT) {
                    throw new ForbiddenException("Only students can join classroom sessions as participants");
                }
            } else if (requireStudentAuth) {
                // If student authentication policy is enabled and no valid user was found, reject with 401
                throw new UnauthorizedException("Student sign in or sign up required to join a classroom session");
            }
        }

        // Step 8: Generate cryptographically unguessable 32-byte participant token
        String rawToken = CryptoUtils.generateSecureToken(32);

        // Step 9: Hash the token using SHA-256 for zero-knowledge persistent storage
        String tokenHash = CryptoUtils.hashToken(rawToken);

        // Step 10: Build persistent Participant entity
        Participant participant = Participant.builder()
                .id(UUID.randomUUID()) // Assign unique UUID
                .session(session) // Link to session
                .displayName(displayName) // Store chosen nickname
                .tokenHash(tokenHash) // Store SHA-256 hash (never the raw token)
                .isRevoked(false) // Initially active
                .joinedAt(now) // Timestamp enrollment
                .lastSeenAt(now) // Initialize activity heartbeat
                .student(studentUser) // Link to registered student account if authenticated
                .build();

        // Step 11: Save participant into database
        Participant saved = participantRepository.save(participant);

        // Step 12: Broadcast real-time PARTICIPANT_JOINED event across session WebSocket channel
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

        // Step 13: Assemble response returning the rawToken one-time to the joining client
        return JoinSessionResponse.builder()
                .participantId(saved.getId())
                .sessionId(session.getId())
                .displayName(saved.getDisplayName())
                .participantToken(rawToken) // Return raw unhashed secret token
                .joinedAt(saved.getJoinedAt())
                .build();
    }

    /**
     * Inspects current participant profile using their session token and updates presence heartbeat.
     *
     * @param participantToken secret token string
     * @return public {@link ParticipantDto}
     */
    @Transactional
    public ParticipantDto getMe(String participantToken) {
        // Step 1: Authenticate participant token and update lastSeenAt timestamp
        Participant participant = participantSecurityService.authenticateParticipant(participantToken);

        // Step 2: Convert to public DTO
        return mapToDto(participant);
    }

    /**
     * Retrieves the active participant roster for an ongoing session.
     * Restricted to authenticated teachers.
     *
     * @param sessionId    session UUID
     * @param teacherToken teacher token header
     * @param teacherKey   master key header
     * @return list of active participant DTOs
     */
    @Transactional
    public List<ParticipantDto> getSessionParticipants(UUID sessionId, String teacherToken, String teacherKey) {
        // Step 1: Validate session existence
        Session session = sessionService.getSessionOrThrow(sessionId);

        // Step 2: Verify instructor credentials
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 3: Query all non-revoked participants in this session and map to DTOs
        return participantRepository.findBySessionIdAndIsRevokedFalse(sessionId)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    /**
     * Revokes a participant's access, kicking them out of the classroom session.
     * Restricted to authenticated teachers.
     *
     * @param sessionId     session UUID
     * @param participantId target participant UUID
     * @param teacherToken  teacher token header
     * @param teacherKey    master key header
     * @throws ResourceNotFoundException if participant does not exist
     * @throws BadRequestException       if participant belongs to another session
     */
    @Transactional
    public void revokeParticipant(UUID sessionId, UUID participantId, String teacherToken, String teacherKey) {
        // Step 1: Validate session existence
        Session session = sessionService.getSessionOrThrow(sessionId);

        // Step 2: Verify teacher permissions
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 3: Fetch participant entity by ID
        Participant participant = participantRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found with ID: " + participantId));

        // Step 4: Ensure participant actually belongs to the specified session
        if (!participant.getSession().getId().equals(sessionId)) {
            throw new BadRequestException("Participant does not belong to session: " + sessionId);
        }

        // Step 5: Mark participant as revoked and persist change
        participant.setRevoked(true);
        participantRepository.save(participant);

        // Step 6: Broadcast real-time PARTICIPANT_REVOKED event so client sockets disconnect
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

    /**
     * Maps a persistent {@link Participant} entity to a public {@link ParticipantDto}.
     *
     * @param participant the entity
     * @return public DTO
     */
    private ParticipantDto mapToDto(Participant participant) {
        return ParticipantDto.builder()
                .id(participant.getId())
                .displayName(participant.getDisplayName())
                .joinedAt(participant.getJoinedAt())
                .lastSeenAt(participant.getLastSeenAt())
                .build();
    }
}
