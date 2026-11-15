package com.classpulse.backend.repository;

import com.classpulse.backend.model.ActivityOption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting and querying {@link ActivityOption} entities.
 * <p>
 * Manages selectable options associated with activities such as MCQs, polls, and ranked prioritizations.
 */
@Repository
public interface ActivityOptionRepository extends JpaRepository<ActivityOption, UUID> {

    /**
     * Retrieves all options defined for a given activity ID, ordered ascending by their display position.
     *
     * @param activityId the UUID of the parent activity
     * @return ordered list of options configured for this activity
     */
    List<ActivityOption> findByActivityIdOrderByDisplayOrderAsc(UUID activityId);

    /**
     * Finds a specific option by its primary key UUID while verifying it belongs to the designated activity ID.
     * Useful for cross-tenant or mismatched activity security checks during response submission.
     *
     * @param id         the option UUID
     * @param activityId the expected parent activity UUID
     * @return Optional containing the matching option if found, or empty Optional otherwise
     */
    java.util.Optional<ActivityOption> findByIdAndActivityId(UUID id, UUID activityId);
}
