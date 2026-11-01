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

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final TeacherSecurityService teacherSecurityService;
    private final AuthService authService;
    private final QrCodeService qrCodeService;
    private final Clock clock;
    private final WebSocketEventPublisher eventPublisher;

    public SessionService(
            SessionRepository sessionRepository,
            TeacherSecurityService teacherSecurityService,
            QrCodeService qrCodeService,
            Clock clock,
            WebSocketEventPublisher eventPublisher) {
        this(sessionRepository, null, null, teacherSecurityService, null, qrCodeService, clock, eventPublisher);
    }

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

    @Value("${classpulse.session.default-duration-hours:24}")
    private int defaultDurationHours = 24;

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, String teacherKey) {
        return createSession(request, teacherKey, null);
    }

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, String teacherKey, String authHeader) {
        teacherSecurityService.verifyTeacherCreationAccess(teacherKey, authHeader);

        User teacherUser = (authService != null) ? authService.resolveUser(authHeader).orElse(null) : null;

        UUID id = UUID.randomUUID();
        String joinCode = generateUniqueJoinCode();
        String rawTeacherToken = CryptoUtils.generateSecureToken(32);
        String teacherTokenHash = CryptoUtils.hashToken(rawTeacherToken);

        Instant now = clock.instant();
        int duration = (request.getDurationHours() != null && request.getDurationHours() > 0)
                ? request.getDurationHours()
                : defaultDurationHours;
        Instant expiresAt = now.plus(Duration.ofHours(duration));

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

        Session saved = sessionRepository.save(session);

        return mapToResponse(saved, rawTeacherToken);
    }

    @Transactional
    public SessionResponse reuseSession(UUID originalSessionId, String customTitle, String teacherToken, String teacherKey, String authHeader) {
        Session originalSession = getSessionOrThrow(originalSessionId);
        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(originalSession, effectiveToken, teacherKey);

        User teacherUser = (authService != null) ? authService.resolveUser(authHeader).orElse(originalSession.getTeacher()) : originalSession.getTeacher();

        UUID newSessionId = UUID.randomUUID();
        String joinCode = generateUniqueJoinCode();
        String rawTeacherToken = CryptoUtils.generateSecureToken(32);
        String teacherTokenHash = CryptoUtils.hashToken(rawTeacherToken);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofHours(defaultDurationHours));

        String newTitle = (customTitle != null && !customTitle.isBlank()) ? customTitle.trim() : originalSession.getTitle();

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

        if (activityRepository != null) {
            List<Activity> originalActivities = activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(originalSessionId);
            for (Activity origAct : originalActivities) {
                UUID newActId = UUID.randomUUID();
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
                activityRepository.save(clonedAct);
            }
        }

        return mapToResponse(savedSession, rawTeacherToken);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> getRecentSessionsForTeacher(String authHeader, String teacherKey) {
        Instant now = clock.instant();
        Instant twentyFourHoursAgo = now.minus(Duration.ofHours(24));

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

        if (teacherSecurityService.isMasterKeyValid(teacherKey)) {
            List<Session> sessions = sessionRepository.findByCreatedAtAfterOrderByCreatedAtDesc(twentyFourHoursAgo);
            return sessions.stream().map(s -> mapToResponse(s, null)).toList();
        }

        throw new UnauthorizedException("Teacher authentication required to view session history");
    }

    @Transactional
    public SessionResponse getSessionById(UUID id, String teacherToken, String teacherKey) {
        return getSessionById(id, teacherToken, teacherKey, null);
    }

    @Transactional
    public SessionResponse getSessionById(UUID id, String teacherToken, String teacherKey, String authHeader) {
        Session session = getSessionOrThrow(id);
        checkAndEnforceExpiry(session);

        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(session, effectiveToken, teacherKey);

        return mapToResponse(session, null);
    }

    @Transactional
    public SessionPublicInfoResponse getPublicSessionInfo(String joinCode) {
        Session session = sessionRepository.findByJoinCodeIgnoreCase(joinCode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with code: " + joinCode));

        checkAndEnforceExpiry(session);

        return SessionPublicInfoResponse.builder()
                .id(session.getId())
                .title(session.getTitle())
                .joinCode(session.getJoinCode())
                .status(session.getStatus())
                .active(session.getStatus() == SessionStatus.ACTIVE)
                .joinUrl(qrCodeService.buildJoinUrl(session.getJoinCode()))
                .build();
    }

    @Transactional
    public SessionResponse closeSession(UUID id, String teacherToken, String teacherKey) {
        return closeSession(id, teacherToken, teacherKey, null);
    }

    @Transactional
    public SessionResponse closeSession(UUID id, String teacherToken, String teacherKey, String authHeader) {
        Session session = getSessionOrThrow(id);
        checkAndEnforceExpiry(session);

        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(session, effectiveToken, teacherKey);

        if (session.getStatus() != SessionStatus.CLOSED) {
            session.setStatus(SessionStatus.CLOSED);
            session.setClosedAt(clock.instant());
            sessionRepository.save(session);

            eventPublisher.publishSessionEvent(
                    WebSocketEventType.SESSION_CLOSED,
                    session.getId(),
                    Map.of("sessionId", session.getId().toString(), "status", "CLOSED")
            );
        }

        return mapToResponse(session, null);
    }

    @Transactional
    public void deleteSession(UUID id, String teacherToken, String teacherKey, String authHeader) {
        Session session = getSessionOrThrow(id);

        String effectiveToken = (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
        teacherSecurityService.verifyTeacherSessionAccess(session, effectiveToken, teacherKey);

        sessionRepository.delete(session);

        eventPublisher.publishSessionEvent(
                WebSocketEventType.SESSION_DELETED,
                id,
                Map.of("sessionId", id.toString())
        );
    }

    @Transactional
    public Session getSessionOrThrow(UUID id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with ID: " + id));
    }

    @Transactional
    public void checkAndEnforceExpiry(Session session) {
        if (session.getStatus() == SessionStatus.ACTIVE && session.isExpired(clock.instant())) {
            session.setStatus(SessionStatus.EXPIRED);
            sessionRepository.save(session);
        }
    }

    private String generateUniqueJoinCode() {
        for (int i = 0; i < 20; i++) {
            String code = CryptoUtils.generateJoinCode(6);
            if (!sessionRepository.existsByJoinCodeIgnoreCase(code)) {
                return code;
            }
        }
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private SessionResponse mapToResponse(Session session, String rawTeacherToken) {
        long participantCount = (participantRepository != null) ? participantRepository.countBySessionIdAndIsRevokedFalse(session.getId()) : 0L;
        String teacherName = session.getTeacher() != null ? session.getTeacher().getFullName() : null;

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
