package com.classpulse.backend.response;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResponseRepository extends JpaRepository<Response, UUID> {

    List<Response> findByActivityId(UUID activityId);

    Optional<Response> findByActivityIdAndParticipantId(UUID activityId, UUID participantId);

    boolean existsByActivityIdAndParticipantId(UUID activityId, UUID participantId);

    long countByActivityId(UUID activityId);

    void deleteByActivityIdAndParticipantId(UUID activityId, UUID participantId);
}
