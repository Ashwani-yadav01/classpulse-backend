package com.classpulse.backend.repository;

import com.classpulse.backend.model.ActivityOption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActivityOptionRepository extends JpaRepository<ActivityOption, UUID> {

    List<ActivityOption> findByActivityIdOrderByDisplayOrderAsc(UUID activityId);

    java.util.Optional<ActivityOption> findByIdAndActivityId(UUID id, UUID activityId);
}
