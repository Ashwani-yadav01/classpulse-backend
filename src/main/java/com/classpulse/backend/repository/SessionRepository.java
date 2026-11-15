package com.classpulse.backend.repository;

import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.User;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting, managing, and securing classroom {@link Session} entities.
 * <p>
 * Features pessimistic row-level locking for concurrency control, case-insensitive join code lookups,
 * teacher token hash lookups, and session history queries.
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * Finds a session by its 6-character alphanumeric join code using case-insensitive matching.
     * Core lookup used when students enter a room code on the landing screen.
     *
     * @param joinCode the code entered by the user (e.g. "ab12cd" or "AB12CD")
     * @return Optional containing the session if matched
     */
    Optional<Session> findByJoinCodeIgnoreCase(String joinCode);

    /**
     * Finds a session by the SHA-256 hash of its teacher token.
     * Used for legacy session-level token authentication headers.
     *
     * @param teacherTokenHash hex-encoded SHA-256 hash
     * @return Optional containing the session if matched
     */
    Optional<Session> findByTeacherTokenHash(String teacherTokenHash);

    /**
     * Checks if a join code is already in use by any session (case-insensitive).
     * Used during random join code generation to ensure absolute uniqueness.
     *
     * @param joinCode the candidate join code
     * @return true if collision detected, false if available
     */
    boolean existsByJoinCodeIgnoreCase(String joinCode);

    /**
     * Retrieves recent sessions owned by a specific teacher created after a given timestamp,
     * ordered from newest to oldest.
     *
     * @param teacher the owner User
     * @param after   the cutoff Instant (e.g. 30 days ago)
     * @return list of matching sessions
     */
    List<Session> findByTeacherAndCreatedAtAfterOrderByCreatedAtDesc(User teacher, Instant after);

    /**
     * Retrieves all sessions owned by a specific teacher, sorted with newest first.
     *
     * @param teacher the owner User
     * @return teacher's session history
     */
    List<Session> findByTeacherOrderByCreatedAtDesc(User teacher);

    /**
     * Retrieves all sessions created across the platform after a given timestamp, sorted newest first.
     *
     * @param after cutoff Instant
     * @return list of recent sessions
     */
    List<Session> findByCreatedAtAfterOrderByCreatedAtDesc(Instant after);

    /**
     * Retrieves a session by its UUID using a pessimistic write lock (SELECT ... FOR UPDATE).
     * Prevents race conditions during critical concurrency operations such as joining or closing a session.
     *
     * @param id the session UUID
     * @return Optional containing the locked session row
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.id = :id")
    Optional<Session> findByIdForUpdate(@Param("id") UUID id);
}
