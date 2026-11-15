package com.classpulse.backend.repository;

import com.classpulse.backend.model.Response;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting, querying, and aggregating participant {@link Response} records.
 * <p>
 * Supports querying responses by activity, participant lookups, submission existence checks,
 * revision updates, and submission count tracking.
 */
@Repository
public interface ResponseRepository extends JpaRepository<Response, UUID> {

    /**
     * Finds all responses submitted to a given activity.
     * Core query used by aggregation services to compute vote counts, word frequencies, and statistics.
     *
     * @param activityId the activity UUID
     * @return all submitted responses for this activity
     */
    List<Response> findByActivityId(UUID activityId);

    /**
     * Finds all responses submitted by a specific participant to an activity.
     *
     * @param activityId    the activity UUID
     * @param participantId the participant UUID
     * @return list of responses by this participant
     */
    List<Response> findByActivityIdAndParticipantId(UUID activityId, UUID participantId);

    /**
     * Retrieves the most recent response submitted by a participant for an activity, ordered by submittedAt descending.
     * Used when revising previous answers or checking current response state.
     *
     * @param activityId    the activity UUID
     * @param participantId the participant UUID
     * @return Optional containing the latest response if present
     */
    Optional<Response> findFirstByActivityIdAndParticipantIdOrderBySubmittedAtDesc(UUID activityId, UUID participantId);

    /**
     * Checks if a participant has already submitted any response to the specified activity.
     * Used to enforce single-submission constraints when revisions are disabled.
     *
     * @param activityId    the activity UUID
     * @param participantId the participant UUID
     * @return true if a response already exists, false otherwise
     */
    boolean existsByActivityIdAndParticipantId(UUID activityId, UUID participantId);

    /**
     * Counts the total number of submissions received for an activity.
     *
     * @param activityId the activity UUID
     * @return total response count
     */
    long countByActivityId(UUID activityId);

    /**
     * Deletes all responses submitted by a specific participant for a given activity.
     * Used during complete answer revisions or rollbacks.
     *
     * @param activityId    the activity UUID
     * @param participantId the participant UUID
     */
    void deleteByActivityIdAndParticipantId(UUID activityId, UUID participantId);
}
