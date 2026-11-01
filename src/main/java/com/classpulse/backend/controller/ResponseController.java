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

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/activities/{activityId}")
@RequiredArgsConstructor
public class ResponseController {

    private final ResponseService responseService;

    private String resolveTeacherToken(String teacherToken, String authHeader) {
        return (authHeader != null && !authHeader.isBlank()) ? authHeader : teacherToken;
    }

    @PostMapping("/responses")
    public ResponseEntity<SubmitResponseResponse> submitResponse(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @Valid @RequestBody SubmitResponseRequest request,
            @RequestHeader("X-Participant-Token") String participantToken) {
        SubmitResponseResponse response = responseService.submitResponse(sessionId, activityId, request, participantToken);
        HttpStatus status = response.isRevised() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/random-picker/pick")
    public ResponseEntity<RandomPickerResponse> pickRandomStudent(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        RandomPickerResponse response = responseService.pickRandomStudent(sessionId, activityId, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/visibility")
    public ResponseEntity<ActivityResponse> updateVisibility(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestBody ActivityVisibilityRequest request,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        ActivityResponse response = responseService.updateVisibility(sessionId, activityId, request, resolveTeacherToken(teacherToken, authHeader), teacherKey);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/results")
    public ResponseEntity<ActivityResultResponse> getResults(
            @PathVariable UUID sessionId,
            @PathVariable UUID activityId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "X-Participant-Token", required = false) String participantToken) {
        String effectiveTeacherToken = (participantToken != null && !participantToken.isBlank())
                ? teacherToken
                : resolveTeacherToken(teacherToken, authHeader);
        ActivityResultResponse results = responseService.getResults(sessionId, activityId, effectiveTeacherToken, teacherKey, participantToken);
        return ResponseEntity.ok(results);
    }
}
