package com.classpulse.backend.controller;

import com.classpulse.backend.dto.CreateSessionRequest;
import com.classpulse.backend.dto.SessionPublicInfoResponse;
import com.classpulse.backend.dto.SessionResponse;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.service.QrCodeService;
import com.classpulse.backend.service.SessionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API controller managing the lifecycle of classroom sessions.
 * <p>
 * Key operations:
 * <ul>
 *   <li>Creating new sessions ({@code POST /api/v1/sessions}).</li>
 *   <li>Listing teacher's recent sessions ({@code GET /api/v1/sessions}).</li>
 *   <li>Fetching session details by ID ({@code GET /api/v1/sessions/{id}}).</li>
 *   <li>Closing sessions ({@code POST /api/v1/sessions/{id}/close}).</li>
 *   <li>Reusing/cloning past session activities into a new session ({@code POST /api/v1/sessions/{id}/reuse}).</li>
 *   <li>Deleting sessions ({@code DELETE /api/v1/sessions/{id}}).</li>
 *   <li>Fetching public join metadata by join code ({@code GET /api/v1/sessions/code/{joinCode}}).</li>
 *   <li>Generating PNG QR code images for instant mobile scanning ({@code GET /api/v1/sessions/{id}/qr}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    /** Domain service for session persistence and orchestration. */
    private final SessionService sessionService;

    /** ZXing-backed service for dynamic QR code PNG generation. */
    private final QrCodeService qrCodeService;

    /**
     * Initializes and launches a new classroom session.
     *
     * @param request    validated session creation payload
     * @param teacherKey optional master key header
     * @param authHeader optional JWT Authorization header
     * @return HTTP 201 Created with session details and secret teacher token
     */
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Create session through SessionService
        SessionResponse response = sessionService.createSession(request, teacherKey, authHeader);

        // Step 2: Return HTTP 201 Created
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves recent sessions owned by the authenticated teacher or platform-wide (if master key).
     *
     * @param authHeader optional Authorization header
     * @param teacherKey optional master key header
     * @return HTTP 200 OK with list of session summaries
     */
    @GetMapping
    public ResponseEntity<List<SessionResponse>> getRecentTeacherSessions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Fetch recent sessions
        List<SessionResponse> responses = sessionService.getRecentSessionsForTeacher(authHeader, teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves detailed information for a specific session by its primary key UUID.
     *
     * @param id           session UUID
     * @param teacherToken optional session teacher token header
     * @param teacherKey   optional master key header
     * @param authHeader   optional JWT header
     * @return HTTP 200 OK with session details
     */
    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Fetch session validating teacher permissions
        SessionResponse response = sessionService.getSessionById(id, teacherToken, teacherKey, authHeader);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Concludes and closes an active session, rejecting any future responses or joins.
     *
     * @param id           session UUID
     * @param teacherToken optional teacher token header
     * @param teacherKey   optional master key header
     * @param authHeader   optional JWT header
     * @return HTTP 200 OK with updated session status
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<SessionResponse> closeSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Close session and broadcast SESSION_CLOSED event
        SessionResponse response = sessionService.closeSession(id, teacherToken, teacherKey, authHeader);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Clones an existing session's activity definitions into a brand new active session with a fresh join code.
     *
     * @param id           source session UUID
     * @param title        optional replacement title for the new session
     * @param teacherToken optional teacher token header
     * @param teacherKey   optional master key header
     * @param authHeader   optional JWT header
     * @return HTTP 201 Created with new session details
     */
    @PostMapping("/{id}/reuse")
    public ResponseEntity<SessionResponse> reuseSession(
            @PathVariable UUID id,
            @RequestParam(required = false) String title,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Clone session activities into a new session
        SessionResponse response = sessionService.reuseSession(id, title, teacherToken, teacherKey, authHeader);

        // Step 2: Return 201 Created
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Permanently deletes a classroom session and all cascaded activities, responses, and participants.
     *
     * @param id           session UUID
     * @param teacherToken optional teacher token header
     * @param teacherKey   optional master key header
     * @param authHeader   optional JWT header
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Delete session and broadcast SESSION_DELETED event
        sessionService.deleteSession(id, teacherToken, teacherKey, authHeader);

        // Step 2: Return 204 No Content
        return ResponseEntity.noContent().build();
    }

    /**
     * Public lookup endpoint used by student clients before joining to verify join code and check session status.
     *
     * @param joinCode 6-character code
     * @return HTTP 200 OK with public session info
     */
    @GetMapping("/code/{joinCode}")
    public ResponseEntity<SessionPublicInfoResponse> getPublicSessionInfo(@PathVariable String joinCode) {
        // Step 1: Fetch public info
        SessionPublicInfoResponse response = sessionService.getPublicSessionInfo(joinCode);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Dynamically generates a PNG QR code containing the student join URL for classroom projector display.
     *
     * @param id     session UUID
     * @param width  desired image width in pixels (defaults to 300)
     * @param height desired image height in pixels (defaults to 300)
     * @return HTTP 200 OK with raw PNG image byte array
     */
    @GetMapping("/{id}/qr")
    public ResponseEntity<byte[]> getSessionQrCode(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "300") int width,
            @RequestParam(defaultValue = "300") int height) {
        // Step 1: Resolve session entity to fetch join code
        Session session = sessionService.getSessionOrThrow(id);

        // Step 2: Render QR code PNG bytes using ZXing
        byte[] qrBytes = qrCodeService.generateQrCodePng(session.getJoinCode(), width, height);

        // Step 3: Stream PNG image response with inline disposition header
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"session-" + session.getJoinCode() + "-qr.png\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(qrBytes);
    }
}
