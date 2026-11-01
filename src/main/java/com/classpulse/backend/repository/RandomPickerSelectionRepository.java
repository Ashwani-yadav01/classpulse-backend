package com.classpulse.backend.repository;

import com.classpulse.backend.model.RandomPickerSelection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RandomPickerSelectionRepository extends JpaRepository<RandomPickerSelection, UUID> {
    List<RandomPickerSelection> findByActivityIdOrderByPickedAtDesc(UUID activityId);
    List<RandomPickerSelection> findByActivityId(UUID activityId);
    long countByActivityId(UUID activityId);
}
