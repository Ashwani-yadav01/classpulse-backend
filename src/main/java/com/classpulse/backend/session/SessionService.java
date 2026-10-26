package com.classpulse.backend.session;

import com.classpulse.backend.common.CryptoUtils;
import com.classpulse.backend.common.exception.ResourceNotFoundException;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.session.dto.CreateSessionRequest;
import com.classpulse.backend.session.dto.SessionPublicInfoResponse;
import com.classpulse.backend.session.dto.SessionResponse;
import com.classpulse.backend.websocket.event.WebSocketEventPublisher;
import com.classpulse.backend.websocket.event.WebSocketEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final TeacherSecurityService teacherSecurityService;
    private final QrCodeService qrCodeService;
    private final Clock clock;
    private final WebSocketEventPublisher eventPublisher;

    @Value("${classpulse.session.default-duration-hours:24}")
    private int defaultDurationHours = 24;

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, String teacherKey) {
        teacherSecurityService.verifyTeacherCreationAccess(teacherKey);

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
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        Session saved = sessionRepository.save(session);

        return SessionResponse.builder()
                .id(saved.getId())
                .title(saved.getTitle())
                .joinCode(saved.getJoinCode())
                .status(saved.getStatus())
                .teacherToken(rawTeacherToken)
                .joinUrl(qrCodeService.buildJoinUrl(saved.getJoinCode()))
                .createdAt(saved.getCreatedAt())
                .expiresAt(saved.getExpiresAt())
                .build();
    }

    @Transactional
    public SessionResponse getSessionById(UUID id, String teacherToken, String teacherKey) {
        Session session = getSessionOrThrow(id);
        checkAndEnforceExpiry(session);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

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
        Session session = getSessionOrThrow(id);
        checkAndEnforceExpiry(session);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

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
                .build();
    }
}
