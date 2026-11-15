package com.classpulse.backend.repository;

import com.classpulse.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting, querying, and authenticating {@link User} entities.
 * <p>
 * Provides case-insensitive lookups and existence verification based on user email addresses.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds a user account matching an email address, ignoring upper/lower case differences.
     * Core lookup used during teacher and student login authentication workflows.
     *
     * @param email user email address (e.g. "teacher@school.edu")
     * @return Optional containing the User if an account exists
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Checks if a user account already exists with the given email address (case-insensitive).
     * Used during registration to prevent duplicate email enrollments.
     *
     * @param email candidate email address to verify
     * @return true if an account is already registered with this email, false otherwise
     */
    boolean existsByEmailIgnoreCase(String email);
}
