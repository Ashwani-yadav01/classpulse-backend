package com.classpulse.backend.repository;

import com.classpulse.backend.model.PrioritizationRank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for managing individual item rankings ({@link PrioritizationRank})
 * within Prioritization activity responses.
 */
@Repository
public interface PrioritizationRankRepository extends JpaRepository<PrioritizationRank, UUID> {

    /**
     * Finds all ranked items for a specific response, ordered ascending by rank position (1, 2, 3...).
     *
     * @param responseId the parent response UUID
     * @return list of prioritization ranks in order of user priority
     */
    List<PrioritizationRank> findByResponseIdOrderByRankOrderAsc(UUID responseId);

    /**
     * Directly deletes all prioritization rank rows belonging to a response.
     * Clears and flushes the persistence context automatically so that subsequent inserts
     * within the same transaction do not encounter unique constraint collisions.
     *
     * @param responseId the parent response UUID whose ranks are being replaced or cleared
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PrioritizationRank pr WHERE pr.response.id = :responseId")
    void deleteByResponseId(@Param("responseId") UUID responseId);
}
