package com.classpulse.backend.controller;

import com.classpulse.backend.dto.ActivityResponse;
import com.classpulse.backend.dto.CreateActivityRequest;
import com.classpulse.backend.service.ActivityService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API controller managing activities within classroom sessions.
 * <p>
 * Endpoints provide:
 * <ul>
 *   <li>Activity creation ({@code POST /api/v1/sessions/{sessionId}/activities}).</li>
 *   <li>Listing all activities for a session ({@code GET /api/v1/sessions/{sessionId}/activities}).</li>
 *   <li>Fetching a single activity by ID ({@code GET /api/v1/sessions/{sessionId}/activities/{activityId}}).</li>
 *   <li>Fetching currently active question for participants ({@code GET .../active}).</li>
 *   <li>Activating an activity ({@code POST .../{activityId}/activate}).</li>
 *   <li>Closing an activity ({@code POST .../{activityId}/close}).</li>
 *   <li>Advancing to next queued activity in presentation order ({@code POST .../next}).</li>
 *   <li>Reordering activities in the presentation queue ({@code PUT .../reorder}).</li>
 *   <li>Deleting an activity ({@code DELETE .../{activityId}}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/activities")
@RequiredArgsConstructor
public class ActivityController {

    /** Domain service orchestrating activity operations. */
    private final ActivityService activityService;

    /**
     * Helper to resolve the effective teacher token from either the Authorization header or X-Teacher-Token.
     *
     * @param teacherToken optional X-Teacher-Token header
     * @param authHeader   optional Authorization header
     * @return preferred token string
     */
    private String resolveTeacherToken(String teacherToken, String authHeader) {
        return (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
    }

    /**
     * Creates a new activity in the specified session.
     *
     * @param sessionId    session UUID
     * @param request      validated activity payload
     * @param teacherToken optional X-Teacher-Token header
     * @param authHeader   optional Authorization header
     * @param teacherKey   optional X-Teacher-Key header
     * @return HTTP 201 Created with saved activity details
     */
    @PostMapping
    public ResponseEntity<ActivityResponse> createActivity(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateActivityRequest request,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Delegate activity creation to ActivityService
        ActivityResponse response = activityService.createActivity(sessionId, request, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return HTTP 201 Created
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves all activities configured for a session in queue order.
     *
     * @param sessionId    session UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with list of activities
     */
    @GetMapping
    public ResponseEntity<List<ActivityResponse>> getActivities(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Retrieve activities
        List<ActivityResponse> activities = activityService.getActivitiesForSession(sessionId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(activities);
    }

    /**
     * Retrieves a specific activity by its ID within the session.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with activity details
     */
    @GetMapping("/{activityId}")
    public ResponseEntity<ActivityResponse> getActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Fetch activity by ID
        ActivityResponse activity = activityService.getActivityById(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(activity);
    }

    /**
     * Endpoint for participants to fetch the currently live/active activity in the classroom.
     *
     * @param sessionId        session UUID
     * @param participantToken mandatory X-Participant-Token header
     * @return HTTP 200 OK with active activity, or HTTP 204 No Content if no activity is currently live
     */
    @GetMapping("/active")
    public ResponseEntity<ActivityResponse> getActiveActivity(
            @PathVariable UUID sessionId,
            @RequestHeader("X-Participant-Token") String participantToken) {
        // Step 1: Query active activity for participant
        return activityService.getActiveActivityForParticipant(sessionId, participantToken)
                // Step 2: Map to 200 OK if found, or 204 No Content if none active
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Activates an activity, opening it for participant responses and closing any previously active activity.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with updated activity
     */
    @PostMapping("/{activityId}/activate")
    public ResponseEntity<ActivityResponse> activateActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Activate activity and broadcast ACTIVITY_ACTIVATED event
        ActivityResponse response = activityService.activateActivity(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Closes an active activity, locking further submissions.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with updated activity
     */
    @PostMapping("/{activityId}/close")
    public ResponseEntity<ActivityResponse> closeActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Close activity and broadcast ACTIVITY_CLOSED event
        ActivityResponse response = activityService.closeActivity(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Launches the next draft activity in the presentation queue, automatically closing the currently active one.
     *
     * @param sessionId    session UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with newly activated activity
     */
    @PostMapping("/next")
    public ResponseEntity<ActivityResponse> launchNextActivity(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Advance queue and launch next activity
        ActivityResponse response = activityService.launchNextActivity(sessionId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Reorders the presentation sequence of activities in the classroom queue.
     *
     * @param sessionId    session UUID
     * @param activityIds  ordered list of activity UUIDs representing desired sequence
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with full updated list in new order
     */
    @PutMapping("/reorder")
    public ResponseEntity<List<ActivityResponse>> reorderActivities(
            @PathVariable UUID sessionId,
            @RequestBody List<UUID> activityIds,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Update orderIndex fields
        List<ActivityResponse> responses = activityService.reorderActivities(sessionId, activityIds, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(responses);
    }

    /**
     * Deletes an activity and its associated responses and options.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{activityId}")
    public ResponseEntity<Void> deleteActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Delete activity and broadcast ACTIVITY_DELETED event
        activityService.deleteActivity(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 204 No Content
        return ResponseEntity.noContent().build();
    }
}
