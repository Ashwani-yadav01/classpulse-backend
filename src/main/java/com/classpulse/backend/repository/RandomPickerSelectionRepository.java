package com.classpulse.backend.repository;

import com.classpulse.backend.model.RandomPickerSelection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting and querying historical student picks
 * in {@link RandomPickerSelection} entities.
 */
@Repository
public interface RandomPickerSelectionRepository extends JpaRepository<RandomPickerSelection, UUID> {

    /**
     * Retrieves all selections made in a given random picker activity, sorted with newest picks first.
     *
     * @param activityId the activity UUID
     * @return chronological reverse list of student selections
     */
    List<RandomPickerSelection> findByActivityIdOrderByPickedAtDesc(UUID activityId);

    /**
     * Retrieves all selections for an activity in default order.
     *
     * @param activityId the activity UUID
     * @return list of selections
     */
    List<RandomPickerSelection> findByActivityId(UUID activityId);

    /**
     * Counts how many random picks have been executed so far for an activity.
     *
     * @param activityId the activity UUID
     * @return total count of selection events recorded
     */
    long countByActivityId(UUID activityId);
}
