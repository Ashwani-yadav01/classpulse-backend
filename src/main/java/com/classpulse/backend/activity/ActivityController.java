package com.classpulse.backend.activity;

import com.classpulse.backend.activity.dto.ActivityResponse;
import com.classpulse.backend.activity.dto.CreateActivityRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @PostMapping
    public ResponseEntity<ActivityResponse> createActivity(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateActivityRequest request,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.createActivity(sessionId, request, teacherToken, teacherKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ActivityResponse>> getActivities(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        List<ActivityResponse> activities = activityService.getActivitiesForSession(sessionId, teacherToken, teacherKey);
        return ResponseEntity.ok(activities);
    }

    @GetMapping("/{activityId}")
    public ResponseEntity<ActivityResponse> getActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse activity = activityService.getActivityById(sessionId, activityId, teacherToken, teacherKey);
        return ResponseEntity.ok(activity);
    }

    @GetMapping("/active")
    public ResponseEntity<ActivityResponse> getActiveActivity(
            @PathVariable UUID sessionId,
            @RequestHeader("X-Participant-Token") String participantToken) {
        return activityService.getActiveActivityForParticipant(sessionId, participantToken)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{activityId}/activate")
    public ResponseEntity<ActivityResponse> activateActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.activateActivity(sessionId, activityId, teacherToken, teacherKey);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{activityId}/close")
    public ResponseEntity<ActivityResponse> closeActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.closeActivity(sessionId, activityId, teacherToken, teacherKey);
        return ResponseEntity.ok(response);
    }
}
