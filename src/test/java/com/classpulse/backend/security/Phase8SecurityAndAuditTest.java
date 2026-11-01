package com.classpulse.backend.security;

import com.classpulse.backend.dto.ActivityOptionDto;
import com.classpulse.backend.dto.CreateActivityRequest;
import com.classpulse.backend.dto.CreateSessionRequest;
import com.classpulse.backend.dto.JoinSessionRequest;
import com.classpulse.backend.dto.SubmitResponseRequest;
import com.classpulse.backend.exception.ErrorResponse;
import com.classpulse.backend.model.ActivityType;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.repository.SessionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Phase8SecurityAndAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    @DisplayName("Actuator /actuator/health is accessible and returns UP")
    void actuatorHealth_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Malformed JSON returns HTTP 400 with sanitized ErrorResponse")
    void malformedJson_shouldReturnSanitized400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Cross-session attack: participant from Session B cannot submit to Session A activity")
    void crossSessionResponse_shouldBeForbidden() throws Exception {
        // 1. Create Session A and Session B
        SessionCreationResult sessionA = createSession("Session A");
        SessionCreationResult sessionB = createSession("Session B");

        // 2. Create and activate MCQ in Session A
        String activityAId = createAndActivateMcq(sessionA.id(), sessionA.teacherToken(), "Q1 in Session A");

        // 3. Join participant in Session B
        String participantTokenB = joinParticipant(sessionB.joinCode(), "StudentB");

        // 4. Participant B attempts to submit response to Session A's activity
        SubmitResponseRequest submitRequest = SubmitResponseRequest.builder()
                .selectedOptionId(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/sessions/" + sessionA.id() + "/activities/" + activityAId + "/responses")
                        .header("X-Participant-Token", participantTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not belong to session")));
    }

    @Test
    @DisplayName("Closed session rejects participant submissions and active activity queries with 403")
    void closedSession_shouldRejectResponsesAndActiveQueries() throws Exception {
        // 1. Create session and activity
        SessionCreationResult session = createSession("Archived Course");
        String activityId = createAndActivateMcq(session.id(), session.teacherToken(), "MCQ in Closing Session");
        String participantToken = joinParticipant(session.joinCode(), "Charlie");

        // 2. Close session
        mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/close")
                        .header("X-Teacher-Token", session.teacherToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 3. Participant querying active activity receives 403 Forbidden (session is closed)
        mockMvc.perform(get("/api/v1/sessions/" + session.id() + "/activities/active")
                        .header("X-Participant-Token", participantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Classroom session is closed"));

        // 4. Participant submitting response is rejected with 403 Forbidden
        SubmitResponseRequest submitRequest = SubmitResponseRequest.builder()
                .selectedOptionId(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", participantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Classroom session is closed"));
    }

    @Test
    @DisplayName("Expired session dynamically rejects responses with HTTP 410 GONE")
    void expiredSession_shouldRejectResponsesDynamically() throws Exception {
        // 1. Create session and activate MCQ
        SessionCreationResult sessionResult = createSession("Expiring Course");
        String activityId = createAndActivateMcq(sessionResult.id(), sessionResult.teacherToken(), "MCQ before expiry");
        String participantToken = joinParticipant(sessionResult.joinCode(), "Diana");

        // 2. Artificially set expiresAt to the past in database
        Session session = sessionRepository.findById(UUID.fromString(sessionResult.id())).orElseThrow();
        session.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        sessionRepository.save(session);

        // 3. Participant submitting response should trigger dynamic expiry enforcement
        SubmitResponseRequest submitRequest = SubmitResponseRequest.builder()
                .selectedOptionId(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/sessions/" + sessionResult.id() + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", participantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.message").value("Classroom session has expired"));

        // Verify that teacher query confirms session is marked EXPIRED
        mockMvc.perform(get("/api/v1/sessions/" + sessionResult.id())
                        .header("X-Teacher-Token", sessionResult.teacherToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("Random Student Picker requires teacher authorization and rejects non-teachers")
    void randomPicker_requiresTeacherAuth() throws Exception {
        SessionCreationResult session = createSession("Algorithms");

        // Join two participants
        joinParticipant(session.joinCode(), "Eve");
        String studentToken = joinParticipant(session.joinCode(), "Frank");

        // Create and activate Random Picker activity
        CreateActivityRequest pickerReq = CreateActivityRequest.builder()
                .title("Pick Next Presenter")
                .type(ActivityType.RANDOM_PICKER)
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/activities")
                        .header("X-Teacher-Token", session.teacherToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pickerReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String activityId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", session.teacherToken()))
                .andExpect(status().isOk());

        // 1. Unauthenticated pick attempt should fail with 401 Unauthorized
        mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/activities/" + activityId + "/random-picker/pick"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // 2. Participant token pick attempt should fail with 403 Forbidden (requires teacher)
        mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/activities/" + activityId + "/random-picker/pick")
                        .header("X-Teacher-Token", studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // 3. Teacher pick attempt succeeds
        mockMvc.perform(post("/api/v1/sessions/" + session.id() + "/activities/" + activityId + "/random-picker/pick")
                        .header("X-Teacher-Token", session.teacherToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantId").isNotEmpty())
                .andExpect(jsonPath("$.displayName").isNotEmpty());
    }

    @Test
    @DisplayName("QR code endpoint safely clamps absurd dimensions")
    void qrCodeEndpoint_shouldClampSafely() throws Exception {
        SessionCreationResult session = createSession("Networking");

        // Oversized parameters (e.g. 100,000) should be clamped safely without memory exhaustion
        mockMvc.perform(get("/api/v1/sessions/" + session.id() + "/qr?width=100000&height=100000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE));

        // Undersized parameters (e.g. 5) should be clamped to minimum safe dimension
        mockMvc.perform(get("/api/v1/sessions/" + session.id() + "/qr?width=5&height=5"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE));
    }

    @Test
    @DisplayName("IDOR prevention: Teacher A token cannot manipulate Session B")
    void teacherIdorPrevention_shouldRejectTeacherFromAnotherSession() throws Exception {
        SessionCreationResult sessionA = createSession("Classroom A");
        SessionCreationResult sessionB = createSession("Classroom B");

        // Teacher A tries to close Session B
        mockMvc.perform(post("/api/v1/sessions/" + sessionB.id() + "/close")
                        .header("X-Teacher-Token", sessionA.teacherToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // Helper methods
    private record SessionCreationResult(String id, String teacherToken, String joinCode) {}

    private SessionCreationResult createSession(String title) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateSessionRequest.builder().title(title).build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return new SessionCreationResult(
                node.get("id").asText(),
                node.get("teacherToken").asText(),
                node.get("joinCode").asText()
        );
    }

    private String joinParticipant(String joinCode, String displayName) throws Exception {
        JoinSessionRequest joinReq = JoinSessionRequest.builder()
                .joinCode(joinCode)
                .displayName(displayName)
                .build();

        MvcResult res = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinReq)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("participantToken").asText();
    }

    private String createAndActivateMcq(String sessionId, String teacherToken, String title) throws Exception {
        CreateActivityRequest mcqReq = CreateActivityRequest.builder()
                .title(title)
                .type(ActivityType.MCQ)
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Option 1").isCorrect(true).displayOrder(1).build(),
                        ActivityOptionDto.builder().optionText("Option 2").isCorrect(false).displayOrder(2).build()
                ))
                .build();

        // Note: X-Teacher-Key can be used to authenticate teacher
        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mcqReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String activityId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Key", "test-teacher-master-key"))
                .andExpect(status().isOk());

        return activityId;
    }
}