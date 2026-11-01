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

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final QrCodeService qrCodeService;

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionResponse response = sessionService.createSession(request, teacherKey, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> getRecentTeacherSessions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        List<SessionResponse> responses = sessionService.getRecentSessionsForTeacher(authHeader, teacherKey);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionResponse response = sessionService.getSessionById(id, teacherToken, teacherKey, authHeader);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<SessionResponse> closeSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionResponse response = sessionService.closeSession(id, teacherToken, teacherKey, authHeader);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/reuse")
    public ResponseEntity<SessionResponse> reuseSession(
            @PathVariable UUID id,
            @RequestParam(required = false) String title,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionResponse response = sessionService.reuseSession(id, title, teacherToken, teacherKey, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        sessionService.deleteSession(id, teacherToken, teacherKey, authHeader);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/code/{joinCode}")
    public ResponseEntity<SessionPublicInfoResponse> getPublicSessionInfo(@PathVariable String joinCode) {
        SessionPublicInfoResponse response = sessionService.getPublicSessionInfo(joinCode);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/qr")
    public ResponseEntity<byte[]> getSessionQrCode(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "300") int width,
            @RequestParam(defaultValue = "300") int height) {
        Session session = sessionService.getSessionOrThrow(id);
        byte[] qrBytes = qrCodeService.generateQrCodePng(session.getJoinCode(), width, height);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"session-" + session.getJoinCode() + "-qr.png\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(qrBytes);
    }
}
