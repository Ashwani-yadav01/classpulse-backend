package com.classpulse.backend.session;

import com.classpulse.backend.session.dto.CreateSessionRequest;
import com.classpulse.backend.session.dto.SessionPublicInfoResponse;
import com.classpulse.backend.session.dto.SessionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        SessionResponse response = sessionService.createSession(request, teacherKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        SessionResponse response = sessionService.getSessionById(id, teacherToken, teacherKey);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<SessionResponse> closeSession(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Teacher-Token", required = false) String teacherToken,
            @RequestHeader(value = "X-Teacher-Key", required = false) String teacherKey) {
        SessionResponse response = sessionService.closeSession(id, teacherToken, teacherKey);
        return ResponseEntity.ok(response);
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
