package com.classpulse.backend.activity;

import com.classpulse.backend.activity.dto.ActivityOptionDto;
import com.classpulse.backend.activity.dto.CreateActivityRequest;
import com.classpulse.backend.participant.dto.JoinSessionRequest;
import com.classpulse.backend.session.dto.CreateSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ActivityApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String sessionId1;
    private String teacherToken1;
    private String sessionId2;
    private String teacherToken2;
    private String participantToken;

    @BeforeEach
    void setUpSessionsAndParticipant() throws Exception {
        // 1. Create Session 1
        MvcResult res1 = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateSessionRequest.builder().title("Operating Systems").build())))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node1 = objectMapper.readTree(res1.getResponse().getContentAsString());
        sessionId1 = node1.get("id").asText();
        teacherToken1 = node1.get("teacherToken").asText();
        String joinCode1 = node1.get("joinCode").asText();

        // 2. Create Session 2 (for ownership violation tests)
        MvcResult res2 = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateSessionRequest.builder().title("Computer Networks").build())))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node2 = objectMapper.readTree(res2.getResponse().getContentAsString());
        sessionId2 = node2.get("id").asText();
        teacherToken2 = node2.get("teacherToken").asText();

        // 3. Join a student to Session 1
        MvcResult partRes = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder()
                                .joinCode(joinCode1).displayName("Diana").build())))
                .andExpect(status().isCreated())
                .andReturn();

        participantToken = objectMapper.readTree(partRes.getResponse().getContentAsString()).get("participantToken").asText();
    }

    @Test
    void testActivityCreation_Lifecycle_And_ConcurrencyProtection() throws Exception {
        // 1. Invalid configuration: MCQ with only 1 option -> 400 Bad Request
        CreateActivityRequest invalidMcq = CreateActivityRequest.builder()
                .type(ActivityType.MCQ)
                .title("Invalid MCQ")
                .options(List.of(ActivityOptionDto.builder().optionText("Only one option").build()))
                .build();

        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidMcq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Multiple Choice Question (MCQ) must have at least 2 options"));

        // 2. Ownership violation: Teacher 2 attempts to create activity in Session 1 -> 403 Forbidden
        CreateActivityRequest validMcq = CreateActivityRequest.builder()
                .type(ActivityType.MCQ)
                .title("Which CPU scheduling algorithm is non-preemptive?")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("First-Come, First-Served (FCFS)").isCorrect(true).build(),
                        ActivityOptionDto.builder().optionText("Round Robin (RR)").isCorrect(false).build()
                ))
                .build();

        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validMcq)))
                .andExpect(status().isForbidden());

        // 3. Unauthenticated creation (no token) -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validMcq)))
                .andExpect(status().isUnauthorized());

        // 4. Valid creation of Activity 1 (MCQ) by Session 1 Teacher -> 201 Created with status DRAFT
        MvcResult act1Res = mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validMcq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.options.length()").value(2))
                .andReturn();

        String activity1Id = objectMapper.readTree(act1Res.getResponse().getContentAsString()).get("id").asText();

        // 5. Valid creation of Activity 2 (Poll) by Session 1 Teacher -> 201 Created with status DRAFT
        CreateActivityRequest pollReq = CreateActivityRequest.builder()
                .type(ActivityType.POLL)
                .title("Pace check: Is the speed okay?")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Too fast").build(),
                        ActivityOptionDto.builder().optionText("Just right").build(),
                        ActivityOptionDto.builder().optionText("Too slow").build()
                ))
                .build();

        MvcResult act2Res = mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pollReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        String activity2Id = objectMapper.readTree(act2Res.getResponse().getContentAsString()).get("id").asText();

        // 6. Teacher lists activities for Session 1
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 7. Ownership violation: Teacher 2 attempts to activate Activity 1 in Session 1 -> 403 Forbidden
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activity1Id + "/activate")
                        .header("X-Teacher-Token", teacherToken2))
                .andExpect(status().isForbidden());

        // 8. Teacher 1 activates Activity 1 -> 200 OK with status ACTIVE
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activity1Id + "/activate")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.activatedAt").isNotEmpty());

        // 9. Student checks active activity: receives Activity 1 with `isCorrect` hidden
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/active")
                        .header("X-Participant-Token", participantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activity1Id))
                .andExpect(jsonPath("$.options[0].isCorrect").doesNotExist());

        // 10. Concurrency Protection: Teacher 1 attempts to activate Activity 2 while Activity 1 is ACTIVE -> 409 Conflict
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activity2Id + "/activate")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Session already has an active activity: Which CPU scheduling algorithm is non-preemptive?"));

        // 11. Teacher 1 closes Activity 1 -> 200 OK with status CLOSED
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activity1Id + "/close")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // 12. Student checks active activity now -> 204 No Content
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/active")
                        .header("X-Participant-Token", participantToken))
                .andExpect(status().isNoContent());

        // 13. Now Activity 2 can be activated because Activity 1 is closed!
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activity2Id + "/activate")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 14. Attempting to activate already closed Activity 1 -> 400 Bad Request
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activity1Id + "/activate")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot activate an activity that has already been closed"));
    }

    @Test
    void testCreationOfAllSevenActivityTypesInDraft() throws Exception {
        // Verify valid creation of each of the 7 activity types
        ActivityType[] types = ActivityType.values();
        for (ActivityType type : types) {
            CreateActivityRequest.CreateActivityRequestBuilder builder = CreateActivityRequest.builder()
                    .type(type)
                    .title("Sample " + type.name());

            if (type == ActivityType.MCQ || type == ActivityType.POLL || type == ActivityType.PRIORITIZATION) {
                builder.options(List.of(
                        ActivityOptionDto.builder().optionText("Choice A").build(),
                        ActivityOptionDto.builder().optionText("Choice B").build()
                ));
            }

            mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                            .header("X-Teacher-Token", teacherToken1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(builder.build())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value(type.name()))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }
    }
}
