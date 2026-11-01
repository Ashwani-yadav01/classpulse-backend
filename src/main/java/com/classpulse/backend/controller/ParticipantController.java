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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ParticipantController {

    private final ParticipantService participantService;

    @PostMapping("/sessions/join")
    public ResponseEntity<JoinSessionResponse> joinSession(
            @Valid @RequestBody JoinSessionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        JoinSessionResponse response = participantService.joinSession(request, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/sessions/{joinCode}/join")
    public ResponseEntity<JoinSessionResponse> joinSessionWithCode(
            @PathVariable String joinCode,
            @RequestBody JoinSessionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        request.setJoinCode(joinCode);
        JoinSessionResponse response = participantService.joinSession(request, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/participants/me")
    public ResponseEntity<ParticipantDto> getMe(
            @RequestHeader("X-Participant-Token") String participantToken) {
        ParticipantDto response = participantService.getMe(participantToken);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sessions/{sessionId}/participants")
    public ResponseEntity<List<ParticipantDto>> getSessionParticipants(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        List<ParticipantDto> participants = participantService.getSessionParticipants(sessionId, teacherToken, teacherKey);
        return ResponseEntity.ok(participants);
    }

    @DeleteMapping("/sessions/{sessionId}/participants/{participantId}")
    public ResponseEntity<Void> revokeParticipant(
            @PathVariable UUID sessionId,
            @PathVariable UUID participantId,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        participantService.revokeParticipant(sessionId, participantId, teacherToken, teacherKey);
        return ResponseEntity.noContent().build();
    }
}
