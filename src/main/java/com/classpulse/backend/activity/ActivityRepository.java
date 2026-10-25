package com.classpulse.backend.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    List<Activity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    Optional<Activity> findBySessionIdAndStatus(UUID sessionId, ActivityStatus status);

    Optional<Activity> findByIdAndSessionId(UUID id, UUID sessionId);

    boolean existsBySessionIdAndStatus(UUID sessionId, ActivityStatus status);
}
