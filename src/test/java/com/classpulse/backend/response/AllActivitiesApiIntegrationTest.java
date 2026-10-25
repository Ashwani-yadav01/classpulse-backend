package com.classpulse.backend.response;

import com.classpulse.backend.activity.ActivityType;
import com.classpulse.backend.activity.dto.ActivityOptionDto;
import com.classpulse.backend.activity.dto.ActivityVisibilityRequest;
import com.classpulse.backend.activity.dto.CreateActivityRequest;
import com.classpulse.backend.participant.dto.JoinSessionRequest;
import com.classpulse.backend.response.dto.SubmitResponseRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AllActivitiesApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String sessionId;
    private String teacherToken;
    private String joinCode;
    private String studentAToken;
    private String studentBToken;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult sRes = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateSessionRequest.builder().title("Computer Architecture").build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sNode = objectMapper.readTree(sRes.getResponse().getContentAsString());
        sessionId = sNode.get("id").asText();
        teacherToken = sNode.get("teacherToken").asText();
        joinCode = sNode.get("joinCode").asText();

        MvcResult stARes = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder().joinCode(joinCode).displayName("Alice").build())))
                .andExpect(status().isCreated())
                .andReturn();
        studentAToken = objectMapper.readTree(stARes.getResponse().getContentAsString()).get("participantToken").asText();

        MvcResult stBRes = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder().joinCode(joinCode).displayName("Bob").build())))
                .andExpect(status().isCreated())
                .andReturn();
        studentBToken = objectMapper.readTree(stBRes.getResponse().getContentAsString()).get("participantToken").asText();
    }

    @Test
    void testWordCloud_EndToEnd() throws Exception {
        // 1. Create Word Cloud activity with revision disabled
        CreateActivityRequest req = CreateActivityRequest.builder()
                .type(ActivityType.WORD_CLOUD)
                .title("Describe concurrency in one word")
                .configJson("{\"allowRevision\":false,\"resultsVisible\":false}")
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("X-Teacher-Token", teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String activityId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        // 2. Activate activity
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk());

        // 3. Reject blank submission
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("   ").build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Word or phrase cannot be blank"));

        // 4. Student A submits "Threads"
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("Threads").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false));

        // 5. Student A attempts second submission -> 409 Conflict
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("Locks").build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Participant has already submitted a response and revision is not allowed"));

        // 6. Student B submits "threads" (case-insensitive duplicate)
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("threads").build())))
                .andExpect(status().isCreated());

        // 7. Teacher views aggregated results: "threads" normalized count = 2
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(2))
                .andExpect(jsonPath("$.wordCloud[0].count").value(2));

        // 8. Student view forbidden when resultsVisible = false
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testOpenQuestion_EndToEnd() throws Exception {
        CreateActivityRequest req = CreateActivityRequest.builder()
                .type(ActivityType.OPEN_QUESTION)
                .title("Explain the difference between mutex and semaphore")
                .configJson("{\"allowRevision\":true,\"resultsVisible\":true}")
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("X-Teacher-Token", teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String activityId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk());

        // Student A submits answer
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("Mutex has ownership, semaphore is a counter.").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false));

        // Student A revises answer -> 200 OK
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("Mutex is a locking mechanism; semaphore is a signaling mechanism.").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revised").value(true));

        // Teacher views results: sees Alice's display name
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(1))
                .andExpect(jsonPath("$.textAnswers[0].answerText").value("Mutex is a locking mechanism; semaphore is a signaling mechanism."))
                .andExpect(jsonPath("$.textAnswers[0].displayName").value("Alice"));

        // Student views results: display name is masked/null for privacy
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Participant-Token", studentBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.textAnswers[0].displayName").doesNotExist());
    }

    @Test
    void testBrainstorming_EndToEnd() throws Exception {
        CreateActivityRequest req = CreateActivityRequest.builder()
                .type(ActivityType.BRAINSTORMING)
                .title("Ideas for end of semester project")
                .configJson("{\"resultsVisible\":true}")
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("X-Teacher-Token", teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String activityId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk());

        // Student A submits idea
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("Distributed Key-Value Store").build())))
                .andExpect(status().isCreated());

        // Student B submits idea
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().textResponse("Raft Consensus Visualizer").build())))
                .andExpect(status().isCreated());

        // Verify results
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(2))
                .andExpect(jsonPath("$.ideas.length()").value(2));
    }

    @Test
    void testRandomStudentPicker_EndToEnd() throws Exception {
        CreateActivityRequest req = CreateActivityRequest.builder()
                .type(ActivityType.RANDOM_PICKER)
                .title("Pick a presenter")
                .configJson("{\"excludePreviouslyPicked\":true,\"resultsVisible\":true}")
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("X-Teacher-Token", teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String activityId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        // 1. Participant attempting to pick random student is rejected -> 403 Forbidden
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/random-picker/pick")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isUnauthorized());

        // 2. Activate activity
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk());

        // 3. Teacher triggers Pick 1
        MvcResult pick1Res = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/random-picker/pick")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").isNotEmpty())
                .andExpect(jsonPath("$.totalEligible").value(2))
                .andExpect(jsonPath("$.totalPickedSoFar").value(1))
                .andReturn();
        String firstPickedId = objectMapper.readTree(pick1Res.getResponse().getContentAsString()).get("participantId").asText();

        // 4. Teacher triggers Pick 2 -> with excludePreviouslyPicked: true, must pick the OTHER student!
        MvcResult pick2Res = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/random-picker/pick")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPickedSoFar").value(2))
                .andReturn();
        String secondPickedId = objectMapper.readTree(pick2Res.getResponse().getContentAsString()).get("participantId").asText();

        assertThat(firstPickedId).isNotEqualTo(secondPickedId);

        // 5. Pick 3 when all 2 participants already picked -> 409 Conflict
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/random-picker/pick")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("All eligible participants have already been picked in this activity"));

        // 6. Check results
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.randomPicker.history.length()").value(2))
                .andExpect(jsonPath("$.randomPicker.totalPickedSoFar").value(2));
    }

    @Test
    void testPrioritization_EndToEnd_WithBordaCount() throws Exception {
        CreateActivityRequest req = CreateActivityRequest.builder()
                .type(ActivityType.PRIORITIZATION)
                .title("Rank project priorities")
                .configJson("{\"allowRevision\":true,\"resultsVisible\":true}")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Security").build(),
                        ActivityOptionDto.builder().optionText("Performance").build(),
                        ActivityOptionDto.builder().optionText("Usability").build()
                ))
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("X-Teacher-Token", teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode actNode = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String activityId = actNode.get("id").asText();
        String secOptId = actNode.get("options").get(0).get("id").asText();
        String perfOptId = actNode.get("options").get(1).get("id").asText();
        String useOptId = actNode.get("options").get(2).get("id").asText();

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk());

        // Reject invalid ranking: duplicate option ID
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder()
                                .orderedOptionIds(List.of(UUID.fromString(secOptId), UUID.fromString(secOptId), UUID.fromString(useOptId)))
                                .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Duplicate option IDs found in prioritization ranking"));

        // Reject invalid ranking: incomplete list
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder()
                                .orderedOptionIds(List.of(UUID.fromString(secOptId), UUID.fromString(perfOptId)))
                                .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ordered option list must contain exactly 3 options"));

        // Student A votes: 1. Security, 2. Performance, 3. Usability
        // Borda points: Security: 3 pts, Performance: 2 pts, Usability: 1 pt
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder()
                                .orderedOptionIds(List.of(UUID.fromString(secOptId), UUID.fromString(perfOptId), UUID.fromString(useOptId)))
                                .build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false));

        // Student B votes: 1. Security, 2. Usability, 3. Performance
        // Borda points: Security: 3 pts, Usability: 2 pts, Performance: 1 pt
        // Totals so far: Security = 6 pts (rank 1), Performance = 3 pts (rank 2), Usability = 3 pts (rank 2)
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder()
                                .orderedOptionIds(List.of(UUID.fromString(secOptId), UUID.fromString(useOptId), UUID.fromString(perfOptId)))
                                .build())))
                .andExpect(status().isCreated());

        // Check intermediate Borda scores
        MvcResult res1 = mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(2))
                .andExpect(jsonPath("$.options[0].optionText").value("Security"))
                .andExpect(jsonPath("$.options[0].score").value(6))
                .andExpect(jsonPath("$.options[0].rank").value(1))
                .andReturn();

        // Student A revises ranking: 1. Performance, 2. Usability, 3. Security
        // Now Student A gives: Performance = 3 pts, Usability = 2 pts, Security = 1 pt
        // Student B gave: Security = 3 pts, Usability = 2 pts, Performance = 1 pt
        // New totals: Performance = 4 pts, Usability = 4 pts, Security = 4 pts! (3-way tie)
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder()
                                .orderedOptionIds(List.of(UUID.fromString(perfOptId), UUID.fromString(useOptId), UUID.fromString(secOptId)))
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revised").value(true));

        // Total votes remain 2, no vote inflation
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResponses").value(2))
                .andExpect(jsonPath("$.options[0].score").value(4))
                .andExpect(jsonPath("$.options[1].score").value(4))
                .andExpect(jsonPath("$.options[2].score").value(4));
    }
}
