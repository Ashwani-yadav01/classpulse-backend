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

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    private String resolveTeacherToken(String teacherToken, String authHeader) {
        return (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
    }

    @PostMapping
    public ResponseEntity<ActivityResponse> createActivity(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateActivityRequest request,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.createActivity(sessionId, request, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ActivityResponse>> getActivities(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        List<ActivityResponse> activities = activityService.getActivitiesForSession(sessionId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(activities);
    }

    @GetMapping("/{activityId}")
    public ResponseEntity<ActivityResponse> getActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse activity = activityService.getActivityById(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
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
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.activateActivity(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{activityId}/close")
    public ResponseEntity<ActivityResponse> closeActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.closeActivity(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/next")
    public ResponseEntity<ActivityResponse> launchNextActivity(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = activityService.launchNextActivity(sessionId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/reorder")
    public ResponseEntity<List<ActivityResponse>> reorderActivities(
            @PathVariable UUID sessionId,
            @RequestBody List<UUID> activityIds,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        List<ActivityResponse> responses = activityService.reorderActivities(sessionId, activityIds, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{activityId}")
    public ResponseEntity<Void> deleteActivity(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        activityService.deleteActivity(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.noContent().build();
    }
}
