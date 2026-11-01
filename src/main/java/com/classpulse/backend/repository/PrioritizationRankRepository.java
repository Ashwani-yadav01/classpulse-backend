package com.classpulse.backend.repository;

import com.classpulse.backend.model.PrioritizationRank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrioritizationRankRepository extends JpaRepository<PrioritizationRank, UUID> {

    List<PrioritizationRank> findByResponseIdOrderByRankOrderAsc(UUID responseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PrioritizationRank pr WHERE pr.response.id = :responseId")
    void deleteByResponseId(@Param("responseId") UUID responseId);
}
