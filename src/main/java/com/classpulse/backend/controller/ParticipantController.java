package com.classpulse.backend.controller;

import com.classpulse.backend.dto.JoinSessionRequest;
import com.classpulse.backend.dto.JoinSessionResponse;
import com.classpulse.backend.dto.ParticipantDto;
import com.classpulse.backend.service.ParticipantService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API controller managing participant lifecycle, session entry, presence, and roster moderation.
 * <p>
 * Key operations:
 * <ul>
 *   <li>Students joining a classroom session using a 6-character code and display nickname.</li>
 *   <li>Participants inspecting their personal session registration status.</li>
 *   <li>Teachers inspecting the active participant roster for a session.</li>
 *   <li>Teachers revoking/kicking disruptive participants from an ongoing session.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ParticipantController {

    /** Underlying domain service executing participant operations. */
    private final ParticipantService participantService;

    /**
     * Enrolls a student into a classroom session using a payload containing the join code and nickname.
     *
     * @param request    validated payload containing join code and unique nickname
     * @param authHeader optional Authorization header if the student is logged in with an account
     * @return HTTP 201 Created with participant ID and secret participantToken
     */
    @PostMapping("/sessions/join")
    public ResponseEntity<JoinSessionResponse> joinSession(
            @Valid @RequestBody JoinSessionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Delegate enrollment to ParticipantService
        JoinSessionResponse response = participantService.joinSession(request, authHeader);

        // Step 2: Return 201 Created with secret participant token
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Convenience REST endpoint allowing the join code to be specified directly in the URL path.
     *
     * @param joinCode   6-character session join code from URL path
     * @param request    incoming join payload
     * @param authHeader optional JWT header
     * @return HTTP 201 Created with participant credentials
     */
    @PostMapping("/sessions/{joinCode}/join")
    public ResponseEntity<JoinSessionResponse> joinSessionWithCode(
            @PathVariable String joinCode,
            @RequestBody JoinSessionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Populate joinCode on request DTO from path parameter
        request.setJoinCode(joinCode);

        // Step 2: Delegate enrollment to ParticipantService
        JoinSessionResponse response = participantService.joinSession(request, authHeader);

        // Step 3: Return 201 Created
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves current participant's session metadata and updates heartbeat presence.
     *
     * @param participantToken secret token passed in X-Participant-Token header
     * @return HTTP 200 OK with public participant info
     */
    @GetMapping("/participants/me")
    public ResponseEntity<ParticipantDto> getMe(
            @RequestHeader("X-Participant-Token") String participantToken) {
        // Step 1: Authenticate participant token and fetch public details
        ParticipantDto response = participantService.getMe(participantToken);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the list of all participants currently enrolled in a session.
     * Restricted to authenticated teachers.
     *
     * @param sessionId    target session UUID
     * @param teacherToken optional X-Teacher-Token header
     * @param teacherKey   optional X-Teacher-Key master key header
     * @return HTTP 200 OK with list of participant DTOs
     */
    @GetMapping("/sessions/{sessionId}/participants")
    public ResponseEntity<List<ParticipantDto>> getSessionParticipants(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Fetch participant roster verifying teacher access
        List<ParticipantDto> participants = participantService.getSessionParticipants(sessionId, teacherToken, teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(participants);
    }

    /**
     * Revokes a participant's access, kicking them out of the classroom session.
     *
     * @param sessionId     target session UUID
     * @param participantId target participant UUID to revoke
     * @param teacherToken  optional X-Teacher-Token header
     * @param teacherKey    optional X-Teacher-Key header
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/sessions/{sessionId}/participants/{participantId}")
    public ResponseEntity<Void> revokeParticipant(
            @PathVariable UUID sessionId,
            @PathVariable UUID participantId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Execute revocation and broadcast PARTICIPANT_REVOKED event
        participantService.revokeParticipant(sessionId, participantId, teacherToken, teacherKey);

        // Step 2: Return 204 No Content
        return ResponseEntity.noContent().build();
    }
}
