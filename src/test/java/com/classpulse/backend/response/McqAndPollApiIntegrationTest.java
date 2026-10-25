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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class McqAndPollApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String sessionId1;
    private String teacherToken1;
    private String joinCode1;

    private String sessionId2;
    private String teacherToken2;
    private String joinCode2;

    private String studentAToken;
    private String studentBToken;
    private String otherSessionStudentToken;

    @BeforeEach
    void setUp() throws Exception {
        // Session 1
        MvcResult s1Res = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateSessionRequest.builder().title("Geography 101").build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode s1Node = objectMapper.readTree(s1Res.getResponse().getContentAsString());
        sessionId1 = s1Node.get("id").asText();
        teacherToken1 = s1Node.get("teacherToken").asText();
        joinCode1 = s1Node.get("joinCode").asText();

        // Session 2 (for cross-session tests)
        MvcResult s2Res = mockMvc.perform(post("/api/v1/sessions")
                        .header("X-Teacher-Key", "test-teacher-master-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateSessionRequest.builder().title("History 201").build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode s2Node = objectMapper.readTree(s2Res.getResponse().getContentAsString());
        sessionId2 = s2Node.get("id").asText();
        teacherToken2 = s2Node.get("teacherToken").asText();
        joinCode2 = s2Node.get("joinCode").asText();

        // Student A in Session 1
        MvcResult stARes = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder().joinCode(joinCode1).displayName("Alice").build())))
                .andExpect(status().isCreated())
                .andReturn();
        studentAToken = objectMapper.readTree(stARes.getResponse().getContentAsString()).get("participantToken").asText();

        // Student B in Session 1
        MvcResult stBRes = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder().joinCode(joinCode1).displayName("Bob").build())))
                .andExpect(status().isCreated())
                .andReturn();
        studentBToken = objectMapper.readTree(stBRes.getResponse().getContentAsString()).get("participantToken").asText();

        // Student in Session 2
        MvcResult stOtherRes = mockMvc.perform(post("/api/v1/sessions/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(JoinSessionRequest.builder().joinCode(joinCode2).displayName("Charlie").build())))
                .andExpect(status().isCreated())
                .andReturn();
        otherSessionStudentToken = objectMapper.readTree(stOtherRes.getResponse().getContentAsString()).get("participantToken").asText();
    }

    @Test
    void testMcq_EndToEndFlow() throws Exception {
        // 1. Create MCQ with 3 options and 1 correct answer
        CreateActivityRequest mcqReq = CreateActivityRequest.builder()
                .type(ActivityType.MCQ)
                .title("What is the capital of France?")
                .configJson("{\"showCorrectAnswer\":false,\"allowRevision\":false,\"resultsVisible\":false}")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Paris").isCorrect(true).build(),
                        ActivityOptionDto.builder().optionText("London").isCorrect(false).build(),
                        ActivityOptionDto.builder().optionText("Berlin").isCorrect(false).build()
                ))
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mcqReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode actNode = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String activityId = actNode.get("id").asText();
        String parisOptionId = actNode.get("options").get(0).get("id").asText();
        String londonOptionId = actNode.get("options").get(1).get("id").asText();

        // 2. Student A attempts to submit response before activation -> 400 Bad Request
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(parisOptionId)).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot submit response to an activity that is not active"));

        // 3. Teacher activates MCQ
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 4. Student A retrieves active activity -> options shown, but isCorrect is hidden
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/active")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].isCorrect").doesNotExist());

        // 5. Cross-session rejection: Student from Session 2 attempts to submit -> 403 Forbidden
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", otherSessionStudentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(parisOptionId)).build())))
                .andExpect(status().isForbidden());

        // 6. Student A submits answer "Paris" -> 201 Created
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(parisOptionId)).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false))
                .andExpect(jsonPath("$.selectedOptionId").value(parisOptionId));

        // 7. Student A attempts to change answer when revision is disabled -> 409 Conflict
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(londonOptionId)).build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Participant has already submitted a response and revision is not allowed"));

        // 8. Student A attempts to view results while resultsVisible is false -> 403 Forbidden
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Results are not yet visible to participants"));

        // 9. Teacher views results -> 200 OK with correct answer revealed
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(1))
                .andExpect(jsonPath("$.options[0].count").value(1))
                .andExpect(jsonPath("$.options[0].percentage").value(100.0))
                .andExpect(jsonPath("$.options[0].isCorrect").value(true))
                .andExpect(jsonPath("$.options[1].count").value(0))
                .andExpect(jsonPath("$.options[1].isCorrect").value(false));

        // 10. Teacher updates visibility: resultsVisible = true, showCorrectAnswer = false
        mockMvc.perform(patch("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/visibility")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ActivityVisibilityRequest.builder().resultsVisible(true).showCorrectAnswer(false).build())))
                .andExpect(status().isOk());

        // 11. Student A views results -> 200 OK, results visible, but isCorrect is hidden
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(1))
                .andExpect(jsonPath("$.options[0].count").value(1))
                .andExpect(jsonPath("$.options[0].isCorrect").doesNotExist());

        // 12. Teacher updates visibility: showCorrectAnswer = true
        mockMvc.perform(patch("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/visibility")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ActivityVisibilityRequest.builder().showCorrectAnswer(true).build())))
                .andExpect(status().isOk());

        // 13. Student A views results again -> isCorrect is now visible to participants
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Participant-Token", studentAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].isCorrect").value(true));

        // 14. Student B submits answer "London" -> 201 Created
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(londonOptionId)).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false));

        // 15. Teacher verifies aggregated 50/50 vote results
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(2))
                .andExpect(jsonPath("$.options[0].count").value(1))
                .andExpect(jsonPath("$.options[0].percentage").value(50.0))
                .andExpect(jsonPath("$.options[1].count").value(1))
                .andExpect(jsonPath("$.options[1].percentage").value(50.0));

        // 16. Teacher closes activity
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/close")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 17. Submissions to closed activity are rejected -> 400 Bad Request
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(parisOptionId)).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot submit response to an activity that is not active"));
    }

    @Test
    void testPoll_EndToEndFlow_WithVoteRevision() throws Exception {
        // 1. Create Poll with 3 options and allowVoteRevision enabled
        CreateActivityRequest pollReq = CreateActivityRequest.builder()
                .type(ActivityType.POLL)
                .title("Should homework be optional?")
                .configJson("{\"allowVoteRevision\":true,\"resultsVisible\":true}")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Yes").build(),
                        ActivityOptionDto.builder().optionText("No").build(),
                        ActivityOptionDto.builder().optionText("Undecided").build()
                ))
                .build();

        MvcResult createRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities")
                        .header("X-Teacher-Token", teacherToken1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pollReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode actNode = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String activityId = actNode.get("id").asText();
        String yesOptionId = actNode.get("options").get(0).get("id").asText();
        String noOptionId = actNode.get("options").get(1).get("id").asText();
        String undecidedOptionId = actNode.get("options").get(2).get("id").asText();

        // 2. Activate Poll
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/activate")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk());

        // 3. Student A votes "Yes" -> 201 Created
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(yesOptionId)).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false));

        // 4. Student B votes "No" -> 201 Created
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(noOptionId)).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revised").value(false));

        // 5. Initial results check: Total votes = 2 (Yes: 1 (50%), No: 1 (50%), Undecided: 0 (0%))
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(2))
                .andExpect(jsonPath("$.options[0].count").value(1))
                .andExpect(jsonPath("$.options[0].percentage").value(50.0))
                .andExpect(jsonPath("$.options[1].count").value(1))
                .andExpect(jsonPath("$.options[1].percentage").value(50.0))
                .andExpect(jsonPath("$.options[2].count").value(0))
                .andExpect(jsonPath("$.options[2].percentage").value(0.0));

        // 6. Student A revises vote from "Yes" to "Undecided" -> 200 OK with revised = true
        mockMvc.perform(post("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/responses")
                        .header("X-Participant-Token", studentAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SubmitResponseRequest.builder().selectedOptionId(java.util.UUID.fromString(undecidedOptionId)).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revised").value(true))
                .andExpect(jsonPath("$.selectedOptionId").value(undecidedOptionId));

        // 7. Results after revision: Total votes must STILL be 2, "Yes": 0 (0%), "No": 1 (50%), "Undecided": 1 (50%)
        mockMvc.perform(get("/api/v1/sessions/" + sessionId1 + "/activities/" + activityId + "/results")
                        .header("X-Teacher-Token", teacherToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVotes").value(2))
                .andExpect(jsonPath("$.options[0].count").value(0))
                .andExpect(jsonPath("$.options[0].percentage").value(0.0))
                .andExpect(jsonPath("$.options[1].count").value(1))
                .andExpect(jsonPath("$.options[1].percentage").value(50.0))
                .andExpect(jsonPath("$.options[2].count").value(1))
                .andExpect(jsonPath("$.options[2].percentage").value(50.0));
    }
}
