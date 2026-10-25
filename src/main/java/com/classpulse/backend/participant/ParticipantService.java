package com.classpulse.backend.participant;

import com.classpulse.backend.common.CryptoUtils;
import com.classpulse.backend.common.exception.BadRequestException;
import com.classpulse.backend.common.exception.ConflictException;
import com.classpulse.backend.common.exception.ResourceNotFoundException;
import com.classpulse.backend.common.exception.SessionExpiredException;
import com.classpulse.backend.participant.dto.JoinSessionRequest;
import com.classpulse.backend.participant.dto.JoinSessionResponse;
import com.classpulse.backend.participant.dto.ParticipantDto;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.session.Session;
import com.classpulse.backend.session.SessionRepository;
import com.classpulse.backend.session.SessionService;
import com.classpulse.backend.session.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ParticipantService {

    private final ParticipantRepository participantRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final TeacherSecurityService teacherSecurityService;
    private final ParticipantSecurityService participantSecurityService;
    private final Clock clock;

    @Transactional
    public JoinSessionResponse joinSession(JoinSessionRequest request) {
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
                .build();

        Participant saved = participantRepository.save(participant);

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
