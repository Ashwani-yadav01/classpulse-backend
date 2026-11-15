package com.classpulse.backend.repository;

import com.classpulse.backend.model.Participant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for managing {@link Participant} entities.
 * <p>
 * Handles token-based participant authentication lookups, case-insensitive display name uniqueness checks,
 * active participant rosters, and participant counts.
 */
@Repository
public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    /**
     * Resolves a participant by the SHA-256 hash of their secret access token.
     * Core lookup used by security filters and token interceptors for student authorization.
     *
     * @param tokenHash hex-encoded SHA-256 hash string
     * @return Optional containing the authenticated participant if valid
     */
    Optional<Participant> findByTokenHash(String tokenHash);

    /**
     * Finds a participant by session and display name using a case-insensitive match.
     * Enforces unique nickname requirements upon session entry.
     *
     * @param sessionId   the session UUID
     * @param displayName candidate display name (e.g. "Bob")
     * @return Optional containing existing participant if display name is already in use
     */
    Optional<Participant> findBySessionIdAndDisplayNameIgnoreCase(UUID sessionId, String displayName);

    /**
     * Retrieves all active (non-revoked) participants enrolled in a session.
     * Used for eligible pools in Random Student Picker and live attendance lists.
     *
     * @param sessionId the session UUID
     * @return list of currently authorized participants
     */
    List<Participant> findBySessionIdAndIsRevokedFalse(UUID sessionId);

    /**
     * Retrieves all participants (active and revoked) ordered chronologically by join time.
     *
     * @param sessionId the session UUID
     * @return full historical list of session participants
     */
    List<Participant> findBySessionIdOrderByJoinedAtAsc(UUID sessionId);

    /**
     * Counts the total number of active, non-revoked participants in a session.
     *
     * @param sessionId the session UUID
     * @return count of active participants
     */
    long countBySessionIdAndIsRevokedFalse(UUID sessionId);
}
