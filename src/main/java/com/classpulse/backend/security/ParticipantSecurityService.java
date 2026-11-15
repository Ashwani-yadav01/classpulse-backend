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

/**
 * Security service responsible for validating participant tokens, checking revocation status,
 * ensuring session validity, and updating participant heartbeat timestamps.
 */
@Service
@RequiredArgsConstructor
public class ParticipantSecurityService {

    /** Repository for querying and persisting participant records. */
    private final ParticipantRepository participantRepository;

    /** System clock provider for deterministic time checks. */
    private final Clock clock;

    /**
     * Authenticates a participant from their raw opaque token string.
     * <p>
     * Verification workflow:
     * <ol>
     *   <li>Ensure token is present and non-blank.</li>
     *   <li>Hash token with SHA-256 and look up participant record.</li>
     *   <li>Verify participant is not revoked.</li>
     *   <li>Verify session is not expired or closed.</li>
     *   <li>Update participant's {@code lastSeenAt} timestamp for attendance/presence.</li>
     * </ol>
     *
     * @param rawToken raw token received from HTTP header or WebSocket STOMP frame
     * @return validated {@link Participant} entity
     * @throws UnauthorizedException   if token is absent or no matching participant is found
     * @throws ForbiddenException      if participant is revoked or session is closed
     * @throws SessionExpiredException if session lifespan has elapsed
     */
    @Transactional
    public Participant authenticateParticipant(String rawToken) {
        // Step 1: Reject missing or empty token strings
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("Participant token required. Provide X-Participant-Token header.");
        }

        // Step 2: Strip optional Bearer prefix and trim whitespace
        String cleanToken = rawToken.startsWith("Bearer ") ? rawToken.substring(7).trim() : rawToken.trim();

        // Step 3: Compute SHA-256 hash of the incoming token to match against the stored hash
        String tokenHash = CryptoUtils.hashToken(cleanToken);

        // Step 4: Locate participant record in database
        Participant participant = participantRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid participant token"));

        // Step 5: Enforce revocation check (e.g. if teacher kicked this participant)
        if (participant.isRevoked()) {
            throw new ForbiddenException("Participant session token has been revoked");
        }

        // Step 6: Validate parent session state and expiration boundary
        Session session = participant.getSession();
        Instant now = clock.instant();

        // Check if session has exceeded its scheduled lifespan
        if (session.isExpired(now)) {
            session.setStatus(SessionStatus.EXPIRED);
            throw new SessionExpiredException("Classroom session has expired");
        }

        // Check if session has been closed by instructor
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ForbiddenException("Classroom session is closed");
        }

        // Step 7: Record current interaction time as lastSeenAt (heartbeat tracking)
        participant.setLastSeenAt(now);

        // Step 8: Return authenticated participant
        return participant;
    }

    /**
     * Authenticates a participant and verifies that their token specifically belongs
     * to the designated classroom session.
     *
     * @param rawToken  raw participant token string
     * @param sessionId expected session UUID
     * @return validated {@link Participant} entity belonging to the session
     * @throws ForbiddenException if participant token is valid but belongs to a different session
     */
    public Participant authenticateParticipantForSession(String rawToken, UUID sessionId) {
        // First authenticate the token generally
        Participant participant = authenticateParticipant(rawToken);

        // Verify the participant's parent session matches the requested session
        if (!participant.getSession().getId().equals(sessionId)) {
            throw new ForbiddenException("Participant token does not belong to session " + sessionId);
        }

        return participant;
    }
}
