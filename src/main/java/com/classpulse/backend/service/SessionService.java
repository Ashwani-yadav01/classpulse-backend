package com.classpulse.backend.service;

import com.classpulse.backend.dto.CreateSessionRequest;
import com.classpulse.backend.dto.SessionPublicInfoResponse;
import com.classpulse.backend.dto.SessionResponse;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.ResourceNotFoundException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityOption;
import com.classpulse.backend.model.ActivityStatus;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.model.User;
import com.classpulse.backend.model.UserRole;
import com.classpulse.backend.repository.ActivityRepository;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;
import com.classpulse.backend.websocket.WebSocketEventPublisher;
import com.classpulse.backend.websocket.WebSocketEventType;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing classroom session lifecycle, join code allocation, session cloning/reuse,
 * expiration enforcement, and real-time state broadcasts.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Generating guaranteed-unique 6-character classroom join codes.</li>
 *   <li>Creating sessions with configurable lifespan hours and SHA-256 teacher tokens.</li>
 *   <li>Reusing past sessions: deep-cloning all activity templates and choices into a fresh session.</li>
 *   <li>Enforcing temporal expiration (transitioning ACTIVE -> EXPIRED when elapsed).</li>
 *   <li>Broadcasting lifecycle events (SESSION_CLOSED, SESSION_DELETED) via WebSockets.</li>
 * </ul>
 */
@Service
public class SessionService {

    /** Session entity repository. */
    private final SessionRepository sessionRepository;

    /** Activity entity repository for cloning activities during session reuse. */
    private final ActivityRepository activityRepository;

    /** Participant entity repository for counting active attendees. */
    private final ParticipantRepository participantRepository;

    /** Teacher security validator. */
    private final TeacherSecurityService teacherSecurityService;

    /** Auth service for resolving teacher identity from JWTs. */
    private final AuthService authService;

    /** QR code generation service for constructing join URLs. */
    private final QrCodeService qrCodeService;

    /** System clock provider for deterministic time management. */
    private final Clock clock;

    /** Real-time WebSocket event broadcaster. */
    private final WebSocketEventPublisher eventPublisher;

    /**
     * Minimal constructor for lightweight unit tests without full persistence context.
     *
     * @param sessionRepository      session repo
     * @param teacherSecurityService teacher security service
     * @param qrCodeService          QR code service
     * @param clock                  application clock
     * @param eventPublisher         WebSocket event publisher
     */
    public SessionService(
            SessionRepository sessionRepository,
            TeacherSecurityService teacherSecurityService,
            QrCodeService qrCodeService,
            Clock clock,
            WebSocketEventPublisher eventPublisher) {
        this(sessionRepository, null, null, teacherSecurityService, null, qrCodeService, clock, eventPublisher);
    }

    /**
     * Primary constructor injecting all dependencies via Spring IoC.
     *
     * @param sessionRepository      session repo
     * @param activityRepository     activity repo
     * @param participantRepository  participant repo
     * @param teacherSecurityService teacher security service
     * @param authService            auth service
     * @param qrCodeService          QR code service
     * @param clock                  application clock
     * @param eventPublisher         WebSocket event publisher
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SessionService(
            SessionRepository sessionRepository,
            ActivityRepository activityRepository,
            ParticipantRepository participantRepository,
            TeacherSecurityService teacherSecurityService,
            AuthService authService,
            QrCodeService qrCodeService,
            Clock clock,
            WebSocketEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.activityRepository = activityRepository;
        this.participantRepository = participantRepository;
        this.teacherSecurityService = teacherSecurityService;
        this.authService = authService;
        this.qrCodeService = qrCodeService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /** Configurable default duration in hours for newly created sessions (default: 24h). */
    @Value("${classpulse.session.default-duration-hours:24}")
    private int defaultDurationHours = 24;

    /**
     * Creates a new classroom session authorized via master key only.
     *
     * @param request    creation payload
     * @param teacherKey master key header
     * @return created session response
     */
    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, String teacherKey) {
        return createSession(request, teacherKey, null);
    }

    /**
     * Creates and initializes a new classroom session.
     *
     * @param request    creation request containing title and optional durationHours
     * @param teacherKey optional master key string
     * @param authHeader optional JWT Authorization header
     * @return {@link SessionResponse} containing session details and raw teacher token
     */
    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, String teacherKey, String authHeader) {
        // Step 1: Verify caller holds teacher creation permissions (JWT teacher account or master key)
        teacherSecurityService.verifyTeacherCreationAccess(teacherKey, authHeader);

        // Step 2: Resolve owner User entity if caller authenticated with a JWT
        User teacherUser = (authService != null) ? authService.resolveUser(authHeader).orElse(null) : null;

        // Step 3: Generate surrogate primary key UUID
        UUID id = UUID.randomUUID();

        // Step 4: Generate a collision-free 6-character unambiguous alphanumeric join code
        String joinCode = generateUniqueJoinCode();

        // Step 5: Generate unguessable 32-byte raw teacher token
        String rawTeacherToken = CryptoUtils.generateSecureToken(32);

        // Step 6: Hash the token with SHA-256 for secure database storage
        String teacherTokenHash = CryptoUtils.hashToken(rawTeacherToken);

        // Step 7: Calculate scheduled expiration boundary based on requested or default duration
        Instant now = clock.instant();
        int duration = (request.getDurationHours() != null && request.getDurationHours() > 0)
                ? request.getDurationHours()
                : defaultDurationHours;
        Instant expiresAt = now.plus(Duration.ofHours(duration));

        // Step 8: Assemble persistent Session entity
        Session session = Session.builder()
                .id(id)
                .title(request.getTitle().trim())
                .joinCode(joinCode)
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(teacherTokenHash)
                .teacher(teacherUser)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        // Step 9: Save session into database
        Session saved = sessionRepository.save(session);

        // Step 10: Return session response containing the raw teacher token
        return mapToResponse(saved, rawTeacherToken);
    }

    /**
     * Reuses an existing session: deep-clones all its configured activities and options
     * into a new active session with a fresh join code and teacher token.
     *
     * @param originalSessionId source session UUID
     * @param customTitle       optional replacement title for the cloned session
     * @param teacherToken      teacher session token
     * @param teacherKey        master key
     * @param authHeader        JWT header
     * @return new {@link SessionResponse} with cloned activities in DRAFT status
     */
    @Transactional
    public SessionResponse reuseSession(UUID originalSessionId, String customTitle, String teacherToken, String teacherKey, String authHeader) {
        // Step 1: Fetch source session
        Session originalSession = getSessionOrThrow(originalSessionId);

        // Step 2: Determine effective token and verify teacher permissions on original session
        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(originalSession, effectiveToken, teacherKey);

        // Step 3: Resolve teacher owner for the new session
        User teacherUser = (authService != null) ? authService.resolveUser(authHeader).orElse(originalSession.getTeacher()) : originalSession.getTeacher();

        // Step 4: Generate new session credentials (UUID, join code, tokens)
        UUID newSessionId = UUID.randomUUID();
        String joinCode = generateUniqueJoinCode();
        String rawTeacherToken = CryptoUtils.generateSecureToken(32);
        String teacherTokenHash = CryptoUtils.hashToken(rawTeacherToken);

        // Step 5: Establish timestamps
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofHours(defaultDurationHours));
        String newTitle = (customTitle != null && !customTitle.isBlank()) ? customTitle.trim() : originalSession.getTitle();

        // Step 6: Persist new session entity
        Session newSession = Session.builder()
                .id(newSessionId)
                .title(newTitle)
                .joinCode(joinCode)
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(teacherTokenHash)
                .teacher(teacherUser)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
        Session savedSession = sessionRepository.save(newSession);

        // Step 7: Deep-clone all activities and options from the source session
        if (activityRepository != null) {
            List<Activity> originalActivities = activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(originalSessionId);
            for (Activity origAct : originalActivities) {
                UUID newActId = UUID.randomUUID();
                // Clone activity into DRAFT status
                Activity clonedAct = Activity.builder()
                        .id(newActId)
                        .session(savedSession)
                        .type(origAct.getType())
                        .title(origAct.getTitle())
                        .status(ActivityStatus.DRAFT)
                        .configJson(origAct.getConfigJson())
                        .orderIndex(origAct.getOrderIndex())
                        .createdAt(now)
                        .options(new java.util.ArrayList<>())
                        .build();

                // Clone associated options
                if (origAct.getOptions() != null) {
                    for (ActivityOption origOpt : origAct.getOptions()) {
                        ActivityOption clonedOpt = ActivityOption.builder()
                                .id(UUID.randomUUID())
                                .activity(clonedAct)
                                .optionText(origOpt.getOptionText())
                                .isCorrect(origOpt.isCorrect())
                                .displayOrder(origOpt.getDisplayOrder())
                                .build();
                        clonedAct.getOptions().add(clonedOpt);
                    }
                }
                // Save cloned activity
                activityRepository.save(clonedAct);
            }
        }

        // Step 8: Return response with new session details and teacher token
        return mapToResponse(savedSession, rawTeacherToken);
    }

    /**
     * Retrieves recent sessions owned by the teacher within the last 24 hours.
     *
     * @param authHeader JWT Authorization header
     * @param teacherKey optional master key
     * @return list of session responses
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> getRecentSessionsForTeacher(String authHeader, String teacherKey) {
        // Step 1: Calculate 24-hour lookback cutoff instant
        Instant now = clock.instant();
        Instant twentyFourHoursAgo = now.minus(Duration.ofHours(24));

        // Step 2: Query by logged-in teacher account if JWT provided
        if (authHeader != null && !authHeader.isBlank() && authService != null) {
            Optional<User> userOpt = authService.resolveUser(authHeader);
            if (userOpt.isPresent()) {
                User teacherUser = userOpt.get();
                if (teacherUser.getRole() != UserRole.TEACHER) {
                    throw new ForbiddenException("Only teachers can view classroom sessions");
                }
                List<Session> sessions = sessionRepository.findByTeacherAndCreatedAtAfterOrderByCreatedAtDesc(teacherUser, twentyFourHoursAgo);
                return sessions.stream().map(s -> mapToResponse(s, null)).toList();
            }
        }

        // Step 3: Query recent sessions platform-wide if valid master key supplied
        if (teacherSecurityService.isMasterKeyValid(teacherKey)) {
            List<Session> sessions = sessionRepository.findByCreatedAtAfterOrderByCreatedAtDesc(twentyFourHoursAgo);
            return sessions.stream().map(s -> mapToResponse(s, null)).toList();
        }

        // Reject if neither valid teacher JWT nor master key provided
        throw new UnauthorizedException("Teacher authentication required to view session history");
    }

    /**
     * Overload fetching session by ID without auth header.
     *
     * @param id           session UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return session response
     */
    @Transactional
    public SessionResponse getSessionById(UUID id, String teacherToken, String teacherKey) {
        return getSessionById(id, teacherToken, teacherKey, null);
    }

    /**
     * Retrieves a session by ID verifying teacher permissions and enforcing temporal expiry.
     *
     * @param id           session UUID
     * @param teacherToken session teacher token
     * @param teacherKey   master key
     * @param authHeader   JWT header
     * @return session response
     */
    @Transactional
    public SessionResponse getSessionById(UUID id, String teacherToken, String teacherKey, String authHeader) {
        // Step 1: Look up session entity
        Session session = getSessionOrThrow(id);

        // Step 2: Auto-expire if scheduled duration has elapsed
        checkAndEnforceExpiry(session);

        // Step 3: Validate teacher credentials
        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(session, effectiveToken, teacherKey);

        // Step 4: Return session response
        return mapToResponse(session, null);
    }

    /**
     * Retrieves public session discovery info by join code (unauthenticated for student landing).
     *
     * @param joinCode 6-character room code
     * @return public session info
     */
    @Transactional
    public SessionPublicInfoResponse getPublicSessionInfo(String joinCode) {
        // Step 1: Find session by join code (case-insensitive)
        Session session = sessionRepository.findByJoinCodeIgnoreCase(joinCode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with code: " + joinCode));

        // Step 2: Auto-expire if scheduled duration elapsed
        checkAndEnforceExpiry(session);

        // Step 3: Construct public metadata
        return SessionPublicInfoResponse.builder()
                .id(session.getId())
                .title(session.getTitle())
                .joinCode(session.getJoinCode())
                .status(session.getStatus())
                .active(session.getStatus() == SessionStatus.ACTIVE)
                .joinUrl(qrCodeService.buildJoinUrl(session.getJoinCode()))
                .build();
    }

    /**
     * Overload closing session without auth header.
     *
     * @param id           session UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return closed session response
     */
    @Transactional
    public SessionResponse closeSession(UUID id, String teacherToken, String teacherKey) {
        return closeSession(id, teacherToken, teacherKey, null);
    }

    /**
     * Closes an active session, marking status CLOSED, setting closedAt, and broadcasting SESSION_CLOSED.
     *
     * @param id           session UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @param authHeader   JWT header
     * @return updated session response
     */
    @Transactional
    public SessionResponse closeSession(UUID id, String teacherToken, String teacherKey, String authHeader) {
        // Step 1: Fetch session
        Session session = getSessionOrThrow(id);

        // Step 2: Enforce temporal expiry check
        checkAndEnforceExpiry(session);

        // Step 3: Verify teacher permissions
        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(session, effectiveToken, teacherKey);

        // Step 4: If not already closed, transition status and notify WebSocket subscribers
        if (session.getStatus() != SessionStatus.CLOSED) {
            session.setStatus(SessionStatus.CLOSED);
            session.setClosedAt(clock.instant());
            sessionRepository.save(session);

            // Broadcast SESSION_CLOSED event
            eventPublisher.publishSessionEvent(
                    WebSocketEventType.SESSION_CLOSED,
                    session.getId(),
                    Map.of("sessionId", session.getId().toString(), "status", "CLOSED")
            );
        }

        return mapToResponse(session, null);
    }

    /**
     * Deletes a session and broadcasts SESSION_DELETED.
     *
     * @param id           session UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @param authHeader   JWT header
     */
    @Transactional
    public void deleteSession(UUID id, String teacherToken, String teacherKey, String authHeader) {
        // Step 1: Resolve session
        Session session = getSessionOrThrow(id);

        // Step 2: Verify teacher permissions
        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(session, effectiveToken, teacherKey);

        // Step 3: Delete session row from database
        sessionRepository.delete(session);

        // Step 4: Broadcast SESSION_DELETED event
        eventPublisher.publishSessionEvent(
                WebSocketEventType.SESSION_DELETED,
                id,
                Map.of("sessionId", id.toString())
        );
    }

    /**
     * Helper to retrieve a session or throw 404 ResourceNotFoundException.
     *
     * @param id session UUID
     * @return persistent Session entity
     */
    @Transactional
    public Session getSessionOrThrow(UUID id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with ID: " + id));
    }

    /**
     * Checks if an ACTIVE session has passed its expiration time and updates its status to EXPIRED.
     *
     * @param session session to inspect
     */
    @Transactional
    public void checkAndEnforceExpiry(Session session) {
        if (session.getStatus() == SessionStatus.ACTIVE && session.isExpired(clock.instant())) {
            session.setStatus(SessionStatus.EXPIRED);
            sessionRepository.save(session);
        }
    }

    /**
     * Generates a unique 6-character classroom join code, retrying on collision.
     *
     * @return unique uppercase join code
     */
    private String generateUniqueJoinCode() {
        // Retry loop up to 20 attempts
        for (int i = 0; i < 20; i++) {
            String code = CryptoUtils.generateJoinCode(6);
            if (!sessionRepository.existsByJoinCodeIgnoreCase(code)) {
                return code; // Found available code
            }
        }
        // Fallback: derive 8-character string from UUID
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Maps persistent {@link Session} entity to public {@link SessionResponse}.
     *
     * @param session         session entity
     * @param rawTeacherToken raw token string (or null if reading existing session)
     * @return session response DTO
     */
    private SessionResponse mapToResponse(Session session, String rawTeacherToken) {
        // Count active participants
        long participantCount = (participantRepository != null) ? participantRepository.countBySessionIdAndIsRevokedFalse(session.getId()) : 0L;
        // Resolve teacher display name
        String teacherName = session.getTeacher() != null ? session.getTeacher().getFullName() : null;

        // Build response
        return SessionResponse.builder()
                .id(session.getId())
                .title(session.getTitle())
                .joinCode(session.getJoinCode())
                .status(session.getStatus())
                .teacherToken(rawTeacherToken)
                .joinUrl(qrCodeService.buildJoinUrl(session.getJoinCode()))
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .closedAt(session.getClosedAt())
                .participantCount(participantCount)
                .teacherName(teacherName)
                .isOwner(session.getTeacher() != null)
                .build();
    }
}
