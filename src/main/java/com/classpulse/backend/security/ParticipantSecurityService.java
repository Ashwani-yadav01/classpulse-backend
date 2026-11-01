package com.classpulse.backend.security;

import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.exception.SessionExpiredException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.util.CryptoUtils;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ParticipantSecurityService {

    private final ParticipantRepository participantRepository;
    private final Clock clock;

    @Transactional
    public Participant authenticateParticipant(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("Participant token required. Provide X-Participant-Token header.");
        }

        String cleanToken = rawToken.startsWith("Bearer ") ? rawToken.substring(7).trim() : rawToken.trim();
        String tokenHash = CryptoUtils.hashToken(cleanToken);

        Participant participant = participantRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid participant token"));

        if (participant.isRevoked()) {
            throw new ForbiddenException("Participant session token has been revoked");
        }

        Session session = participant.getSession();
        Instant now = clock.instant();

        if (session.isExpired(now)) {
            session.setStatus(SessionStatus.EXPIRED);
            throw new SessionExpiredException("Classroom session has expired");
        }

        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ForbiddenException("Classroom session is closed");
        }

        participant.setLastSeenAt(now);
        return participant;
    }

    public Participant authenticateParticipantForSession(String rawToken, UUID sessionId) {
        Participant participant = authenticateParticipant(rawToken);
        if (!participant.getSession().getId().equals(sessionId)) {
            throw new ForbiddenException("Participant token does not belong to session " + sessionId);
        }
        return participant;
    }
}
