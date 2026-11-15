package com.classpulse.backend.repository;

import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting, querying, and managing {@link Activity} entities.
 * <p>
 * Supports querying activities scoped to specific sessions, checking active activity states,
 * and ordering activities for sequential presentation queues.
 */
@Repository
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    /**
     * Retrieves all activities belonging to a session, ordered chronologically by creation time.
     *
     * @param sessionId the UUID of the session
     * @return list of activities sorted by createdAt ascending
     */
    List<Activity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Retrieves all activities in a session ordered by presentation queue order index first,
     * then breaking ties by creation timestamp.
     *
     * @param sessionId the UUID of the session
     * @return ordered list of activities for sequence navigation
     */
    List<Activity> findBySessionIdOrderByOrderIndexAscCreatedAtAsc(UUID sessionId);

    /**
     * Finds an activity within a session matching a specific lifecycle status (e.g. finding the currently ACTIVE activity).
     *
     * @param sessionId the session UUID
     * @param status    the target lifecycle status (typically {@link ActivityStatus#ACTIVE})
     * @return Optional containing the active activity if present
     */
    Optional<Activity> findBySessionIdAndStatus(UUID sessionId, ActivityStatus status);

    /**
     * Retrieves an activity by its ID while guaranteeing it belongs to the specified session ID.
     * Guards against ID manipulation across different sessions.
     *
     * @param id        the activity UUID
     * @param sessionId the expected parent session UUID
     * @return Optional containing the activity if found and owned by the session
     */
    Optional<Activity> findByIdAndSessionId(UUID id, UUID sessionId);

    /**
     * Checks whether any activity exists within a given session matching the specified status.
     *
     * @param sessionId the session UUID
     * @param status    the target status to check
     * @return true if at least one matching activity exists, false otherwise
     */
    boolean existsBySessionIdAndStatus(UUID sessionId, ActivityStatus status);
}
