package com.classpulse.backend.model;

/**
 * Enumeration representing user security privileges and authorization roles in ClassPulse.
 */
public enum UserRole {
    /**
     * Instructor / Educator role with privileges to create, configure, launch, and manage classroom sessions.
     */
    TEACHER,

    /**
     * Learner / Student role with privileges to join sessions, receive real-time activities, and submit answers.
     */
    STUDENT
}
