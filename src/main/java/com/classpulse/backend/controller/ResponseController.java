package com.classpulse.backend.controller;

import com.classpulse.backend.dto.ActivityResponse;
import com.classpulse.backend.dto.ActivityResultResponse;
import com.classpulse.backend.dto.ActivityVisibilityRequest;
import com.classpulse.backend.dto.RandomPickerResponse;
import com.classpulse.backend.dto.SubmitResponseRequest;
import com.classpulse.backend.dto.SubmitResponseResponse;
import com.classpulse.backend.service.ResponseService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API controller managing participant submissions, random student draws,
 * visibility toggles, and result aggregation queries.
 * <p>
 * Key operations:
 * <ul>
 *   <li>Submitting answers to active activities ({@code POST .../responses}).</li>
 *   <li>Triggering a random student draw ({@code POST .../random-picker/pick}).</li>
 *   <li>Updating result and correct-answer visibility ({@code PATCH .../visibility}).</li>
 *   <li>Querying real-time aggregated results and charts ({@code GET .../results}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/activities/{activityId}")
@RequiredArgsConstructor
public class ResponseController {

    /** Domain service orchestrating submissions, draws, and result aggregations. */
    private final ResponseService responseService;

    /**
     * Resolves the preferred teacher token string from either the Authorization header or X-Teacher-Token.
     *
     * @param teacherToken optional X-Teacher-Token header
     * @param authHeader   optional Authorization header
     * @return preferred token string
     */
    private String resolveTeacherToken(String teacherToken, String authHeader) {
        return (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
    }

    /**
     * Records a participant's submission to an active activity.
     *
     * @param sessionId        session UUID
     * @param activityId       activity UUID
     * @param request          validated response payload (choice, text, or rankings)
     * @param participantToken mandatory X-Participant-Token header
     * @return HTTP 201 Created (initial answer) or HTTP 200 OK (revised answer)
     */
    @PostMapping("/responses")
    public ResponseEntity<SubmitResponseResponse> submitResponse(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @Valid @RequestBody SubmitResponseRequest request,
            @RequestHeader("X-Participant-Token") String participantToken) {
        // Step 1: Process submission in ResponseService
        SubmitResponseResponse response = responseService.submitResponse(sessionId, activityId, request, participantToken);

        // Step 2: Differentiate HTTP status based on revision vs new submission
        HttpStatus status = response.isRevised() ? HttpStatus.OK : HttpStatus.CREATED;

        // Step 3: Return response entity
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Triggers a random student draw in a Random Student Picker activity.
     * Restricted to authenticated teachers.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with selected student info
     */
    @PostMapping("/random-picker/pick")
    public ResponseEntity<RandomPickerResponse> pickRandomStudent(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Select eligible participant at random and broadcast event
        RandomPickerResponse response = responseService.pickRandomStudent(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Toggles whether aggregate results or correct answers are visible to participants.
     * Restricted to authenticated teachers.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param request      visibility toggles
     * @param teacherToken optional teacher token
     * @param authHeader   optional authorization header
     * @param teacherKey   optional master key
     * @return HTTP 200 OK with updated activity metadata
     */
    @PatchMapping("/visibility")
    public ResponseEntity<ActivityResponse> updateVisibility(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestBody ActivityVisibilityRequest request,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        // Step 1: Update configuration and broadcast RESULT_VISIBILITY_CHANGED
        ActivityResponse response = responseService.updateVisibility(sessionId, activityId, request, resolveTeacherToken(teacherToken, authHeader), teacherKey);

        // Step 2: Return 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves aggregated results, vote distributions, word clouds, or ideas for an activity.
     * <p>
     * Available to teachers (full detail including respondent identities) and participants
     * (sanitized aggregate view if results are marked visible).
     *
     * @param sessionId        session UUID
     * @param activityId       activity UUID
     * @param teacherToken     optional teacher token
     * @param authHeader       optional authorization header
     * @param teacherKey       optional master key
     * @param participantToken optional participant token
     * @return HTTP 200 OK with aggregated results
     */
    @GetMapping("/results")
    public ResponseEntity<ActivityResultResponse> getResults(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken) {
        // Step 1: Determine effective teacher token
        String effectiveTeacherToken = (participantToken != null && !participantToken.isBlank())
                ? teacherToken
                : resolveTeacherToken(teacherToken, authHeader);

        // Step 2: Retrieve results from ResponseService
        ActivityResultResponse results = responseService.getResults(sessionId, activityId, effectiveTeacherToken, teacherKey, participantToken);

        // Step 3: Return 200 OK
        return ResponseEntity.ok(results);
    }
}
