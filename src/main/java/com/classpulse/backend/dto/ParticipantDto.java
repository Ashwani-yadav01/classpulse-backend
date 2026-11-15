package com.classpulse.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Public Data Transfer Object representing a classroom participant.
 * <p>
 * Exposes non-sensitive participant metadata (omits token hashes) for rosters and attendance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantDto {

    /**
     * Unique identifier of the participant.
     */
    private UUID id;

    /**
     * Confirmed display nickname of the student.
     */
    private String displayName;

    /**
     * UTC timestamp when the student joined the session.
     */
    private Instant joinedAt;

    /**
     * UTC timestamp of the student's most recent interaction or activity.
     */
    private Instant lastSeenAt;
}
