package com.classpulse.backend.repository;

import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.User;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByJoinCodeIgnoreCase(String joinCode);

    Optional<Session> findByTeacherTokenHash(String teacherTokenHash);

    boolean existsByJoinCodeIgnoreCase(String joinCode);

    List<Session> findByTeacherAndCreatedAtAfterOrderByCreatedAtDesc(User teacher, Instant after);

    List<Session> findByTeacherOrderByCreatedAtDesc(User teacher);

    List<Session> findByCreatedAtAfterOrderByCreatedAtDesc(Instant after);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.id = :id")
    Optional<Session> findByIdForUpdate(@Param("id") UUID id);
}
