package com.classpulse.backend.controller;

import com.classpulse.backend.dto.CreateSessionRequest;
import com.classpulse.backend.dto.JoinSessionRequest;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SessionAndParticipantApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullPhase2SessionAndParticipantSecurityFlow() throws Exception {
        // 1. Unsuccessful Teacher Authentication (invalid teacher key)
        CreateSessionRequest unauthReq = CreateSessionRequest.builder()
                .title("Unauthorized Session")
                .build();

        mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "invalid-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unauthReq)))
                .andExpect(status().isUnauthorized());

        // 2. Successful Session 1 Creation as Teacher
        CreateSessionRequest createReq1 = CreateSessionRequest.builder()
                .title("Advanced Algorithms")
                .durationHours(24)
                .build();

        MvcResult createResult1 = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.joinCode").isNotEmpty())
                .andExpect(jsonPath("$.teacherToken").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode sessionNode1 = objectMapper.readTree(createResult1.getResponse().getContentAsString());
        String session1Id = sessionNode1.get("id").asText();
        String joinCode1 = sessionNode1.get("joinCode").asText();
        String teacher1Token = sessionNode1.get("teacherToken").asText();

        // 3. Create Session 2 (to test ownership violation)
        CreateSessionRequest createReq2 = CreateSessionRequest.builder()
                .title("Database Systems")
                .durationHours(24)
                .build();

        MvcResult createResult2 = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq2)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode sessionNode2 = objectMapper.readTree(createResult2.getResponse().getContentAsString());
        String session2Id = sessionNode2.get("id").asText();
        String joinCode2 = sessionNode2.get("joinCode").asText();
        String teacher2Token = sessionNode2.get("teacherToken").asText();

        // 4. Ownership Violation Test: Teacher 1 attempts to access Session 2 with Teacher 1's token -> 403 Forbidden
        mockMvc.perform(get("/api/v1/sessions/" + session2Id)
                        .header("X-Teacher-Token", teacher1Token))
                .andExpect(status().isForbidden());

        // Accessing Session 2 with Teacher 2's token succeeds -> 200 OK
        mockMvc.perform(get("/api/v1/sessions/" + session2Id)
                        .header("X-Teacher-Token", teacher2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Database Systems"));

        // Accessing Session 2 without any token -> 401 Unauthorized
        mockMvc.perform(get("/api/v1/sessions/" + session2Id))
                .andExpect(status().isUnauthorized());

        // 5. Fetch QR Code PNG for Session 1
        mockMvc.perform(get("/api/v1/sessions/" + session1Id + "/qr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        // 6. Fetch Public Session Info using join code
        mockMvc.perform(get("/api/v1/sessions/code/" + joinCode1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session1Id))
                .andExpect(jsonPath("$.title").value("Advanced Algorithms"))
                .andExpect(jsonPath("$.active").value(true));

        // 7. Student joins with invalid join code -> 404 Not Found
        mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder()
                                .joinCode("NONEXIST").displayName("Alice").build())))
                .andExpect(status().isNotFound());

        // 8. Student Alice joins Session 1
        JoinSessionRequest joinAlice = JoinSessionRequest.builder()
                .joinCode(joinCode1)
                .displayName("Alice")
                .build();

        MvcResult aliceResult = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinAlice)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.participantToken").isNotEmpty())
                .andReturn();

        JsonNode aliceNode = objectMapper.readTree(aliceResult.getResponse().getContentAsString());
        String aliceToken = aliceNode.get("participantToken").asText();
        String aliceId = aliceNode.get("participantId").asText();

        // 9. Duplicate join with same display name in same session -> 409 Conflict
        mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinAlice)))
                .andExpect(status().isConflict());

        // 10. Student with same display name joining Session 2 -> Allowed (201 Created)
        JoinSessionRequest joinAliceSess2 = JoinSessionRequest.builder()
                .joinCode(joinCode2)
                .displayName("Alice")
                .build();

        mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinAliceSess2)))
                .andExpect(status().isCreated());

        // 11. Participant lookup /me with valid token -> 200 OK
        mockMvc.perform(get("/api/v1/participants/me")
                        .header("X-Participant-Token", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice"));

        // 12. Participant lookup /me with invalid token -> 401 Unauthorized
        mockMvc.perform(get("/api/v1/participants/me")
                        .header("X-Participant-Token", "invalid-token-12345"))
                .andExpect(status().isUnauthorized());

        // 13. Student Bob joins Session 1 using path parameter endpoint
        JoinSessionRequest joinBob = JoinSessionRequest.builder()
                .displayName("Bob")
                .build();

        MvcResult bobResult = mockMvc.perform(post("/api/v1/sessions/" + joinCode1 + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinBob)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Bob"))
                .andReturn();

        JsonNode bobNode = objectMapper.readTree(bobResult.getResponse().getContentAsString());
        String bobId = bobNode.get("participantId").asText();
        String bobToken = bobNode.get("participantToken").asText();

        // 14. Teacher lists participants in Session 1
        mockMvc.perform(get("/api/v1/sessions/" + session1Id + "/participants")
                        .header("X-Teacher-Token", teacher1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 15. Teacher revokes Bob's token
        mockMvc.perform(delete("/api/v1/sessions/" + session1Id + "/participants/" + bobId)
                        .header("X-Teacher-Token", teacher1Token))
                .andExpect(status().isNoContent());

        // 16. Revoked participant Bob attempts to access /participants/me -> 403 Forbidden
        mockMvc.perform(get("/api/v1/participants/me")
                        .header("X-Participant-Token", bobToken))
                .andExpect(status().isForbidden());

        // 17. Teacher closes Session 1
        mockMvc.perform(post("/api/v1/sessions/" + session1Id + "/close")
                        .header("X-Teacher-Token", teacher1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 18. Attempting to join closed session fails -> 400 Bad Request
        JoinSessionRequest joinCharlie = JoinSessionRequest.builder()
                .joinCode(joinCode1)
                .displayName("Charlie")
                .build();

        mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinCharlie)))
                .andExpect(status().isBadRequest());
    }
}
