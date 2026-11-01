package com.classpulse.backend.repository;

import com.classpulse.backend.model.Participant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    Optional<Participant> findByTokenHash(String tokenHash);

    Optional<Participant> findBySessionIdAndDisplayNameIgnoreCase(UUID sessionId, String displayName);

    List<Participant> findBySessionIdAndIsRevokedFalse(UUID sessionId);

    List<Participant> findBySessionIdOrderByJoinedAtAsc(UUID sessionId);

    long countBySessionIdAndIsRevokedFalse(UUID sessionId);
}
