package com.classpulse.backend.controller;

import com.classpulse.backend.dto.CreateSessionRequest;
import com.classpulse.backend.dto.JoinSessionRequest;
import com.classpulse.backend.dto.LoginRequest;
import com.classpulse.backend.dto.RegisterRequest;
import com.classpulse.backend.model.UserRole;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.classpulse.backend.service.ParticipantService participantService;

    @Test
    @DisplayName("Teacher and Student account registration, login, session creation, 24h recent sessions, and student join")
    void endToEndAuthAndSessionFlow() throws Exception {
        // 1. Register Teacher Account
        RegisterRequest teacherReg = RegisterRequest.builder()
                .email("professor.smith@university.edu")
                .password("securePassword123")
                .fullName("Prof. John Smith")
                .role(UserRole.TEACHER)
                .build();

        MvcResult teacherRegRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(teacherReg)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.email").value("professor.smith@university.edu"))
                .andExpect(jsonPath("$.user.role").value("TEACHER"))
                .andReturn();

        JsonNode teacherNode = objectMapper.readTree(teacherRegRes.getResponse().getContentAsString());
        String teacherToken = teacherNode.path("token").asText();

        // 2. Duplicate registration should fail with 409 Conflict
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(teacherReg)))
                .andExpect(status().isConflict());

        // 3. Login with Teacher Account
        LoginRequest loginReq = LoginRequest.builder()
                .email("professor.smith@university.edu")
                .password("securePassword123")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());

        // 4. GET /api/v1/auth/me
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Prof. John Smith"));

        // 5. Teacher creates session using JWT (no master key needed!)
        CreateSessionRequest sessionReq = CreateSessionRequest.builder()
                .title("Advanced Distributed Systems")
                .durationHours(24)
                .build();

        MvcResult createSessRes = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessionReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.joinCode").isString())
                .andReturn();

        JsonNode sessionNode = objectMapper.readTree(createSessRes.getResponse().getContentAsString());
        String joinCode = sessionNode.path("joinCode").asText();
        String sessionId = sessionNode.path("id").asText();

        // 6. Teacher views sessions opened in last 24 hours
        MvcResult recentRes = mockMvc.perform(get("/api/v1/sessions")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode sessionsList = objectMapper.readTree(recentRes.getResponse().getContentAsString());
        assertThat(sessionsList.isArray()).isTrue();
        assertThat(sessionsList.size()).isGreaterThanOrEqualTo(1);

        // 7. Register Student Account
        RegisterRequest studentReg = RegisterRequest.builder()
                .email("alice.student@university.edu")
                .password("studentPass456")
                .fullName("Alice Wonderland")
                .role(UserRole.STUDENT)
                .build();

        MvcResult studentRegRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(studentReg)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode studentNode = objectMapper.readTree(studentRegRes.getResponse().getContentAsString());
        String studentToken = studentNode.path("token").asText();

        // 8. Student joins session using account
        JoinSessionRequest joinReq = JoinSessionRequest.builder()
                .joinCode(joinCode)
                .displayName("Alice Wonderland")
                .build();

        mockMvc.perform(post("/api/v1/sessions/join")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Alice Wonderland"))
                .andExpect(jsonPath("$.sessionId").value(sessionId));
    }

    @Test
    @DisplayName("Sessions are hidden without teacher login - returns 401 Unauthorized")
    void getRecentSessions_withoutTeacherLogin_shouldFailWith401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Teacher B cannot see or access Teacher A's session - returns 403 Forbidden")
    void teacherSessionIsolation_shouldRejectOtherTeacher() throws Exception {
        // 1. Register Teacher A
        RegisterRequest teacherAReq = RegisterRequest.builder()
                .email("teacherA@university.edu")
                .password("password123")
                .fullName("Teacher A")
                .role(UserRole.TEACHER)
                .build();
        MvcResult resA = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(teacherAReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String tokenA = objectMapper.readTree(resA.getResponse().getContentAsString()).path("token").asText();

        // 2. Register Teacher B
        RegisterRequest teacherBReq = RegisterRequest.builder()
                .email("teacherB@university.edu")
                .password("password123")
                .fullName("Teacher B")
                .role(UserRole.TEACHER)
                .build();
        MvcResult resB = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(teacherBReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String tokenB = objectMapper.readTree(resB.getResponse().getContentAsString()).path("token").asText();

        // 3. Teacher A creates a session
        CreateSessionRequest sessionReq = CreateSessionRequest.builder()
                .title("Teacher A's Private Class")
                .build();
        MvcResult sessRes = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessionReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionAId = objectMapper.readTree(sessRes.getResponse().getContentAsString()).path("id").asText();

        // 4. Teacher A can access session A
        mockMvc.perform(get("/api/v1/sessions/" + sessionAId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // 5. Teacher B attempts to access Teacher A's session -> 403 Forbidden
        mockMvc.perform(get("/api/v1/sessions/" + sessionAId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Student cannot join without sign in when student auth is enabled - returns 401")
    void studentCannotJoinWithoutAuth_whenEnabled() throws Exception {
        participantService.setRequireStudentAuth(true);
        try {
            // Register Teacher and create session
            RegisterRequest teacherReq = RegisterRequest.builder()
                    .email("prof.security@university.edu")
                    .password("securePassword123")
                    .fullName("Prof Security")
                    .role(UserRole.TEACHER)
                    .build();
            MvcResult tRes = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(teacherReq)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String tToken = objectMapper.readTree(tRes.getResponse().getContentAsString()).path("token").asText();

            CreateSessionRequest sessReq = CreateSessionRequest.builder().title("Secure Lecture").build();
            MvcResult sRes = mockMvc.perform(post("/api/v1/sessions")
                            .header("Authorization", "Bearer " + tToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sessReq)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String joinCode = objectMapper.readTree(sRes.getResponse().getContentAsString()).path("joinCode").asText();

            // Attempt to join anonymously without auth header
            JoinSessionRequest unauthJoin = JoinSessionRequest.builder()
                    .joinCode(joinCode)
                    .displayName("Anonymous Bob")
                    .build();

            mockMvc.perform(post("/api/v1/sessions/join")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(unauthJoin)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        } finally {
            participantService.setRequireStudentAuth(false);
        }
    }

    @Test
    @DisplayName("Authenticated student with Authorization header can fetch results with participant token without triggering Teacher role error")
    void studentWithAccount_canAccessResultsWithParticipantToken() throws Exception {
        // 1. Register Teacher and create session
        RegisterRequest teacherReq = RegisterRequest.builder()
                .email("teacher.results@university.edu")
                .password("password123")
                .fullName("Teacher Results")
                .role(UserRole.TEACHER)
                .build();
        MvcResult tRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(teacherReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String tToken = objectMapper.readTree(tRes.getResponse().getContentAsString()).path("token").asText();

        CreateSessionRequest sessReq = CreateSessionRequest.builder().title("Poll Session").build();
        MvcResult sRes = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + tToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessReq)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sessNode = objectMapper.readTree(sRes.getResponse().getContentAsString());
        String sessionId = sessNode.path("id").asText();
        String joinCode = sessNode.path("joinCode").asText();

        // 2. Create activity with results visible = true
        com.classpulse.backend.dto.CreateActivityRequest pollReq = com.classpulse.backend.dto.CreateActivityRequest.builder()
                .title("Favorite Language")
                .type(com.classpulse.backend.model.ActivityType.POLL)
                .configJson("{\"resultsVisible\":true}")
                .options(java.util.List.of(
                        com.classpulse.backend.dto.ActivityOptionDto.builder().optionText("Java").displayOrder(1).build(),
                        com.classpulse.backend.dto.ActivityOptionDto.builder().optionText("Python").displayOrder(2).build()
                ))
                .build();
        MvcResult actRes = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities")
                        .header("Authorization", "Bearer " + tToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pollReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String activityId = objectMapper.readTree(actRes.getResponse().getContentAsString()).path("id").asText();

        // Activate activity
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/activate")
                        .header("Authorization", "Bearer " + tToken))
                .andExpect(status().isOk());

        // 3. Register Student
        RegisterRequest studentReg = RegisterRequest.builder()
                .email("student.results@university.edu")
                .password("studentPass123")
                .fullName("Student Results")
                .role(UserRole.STUDENT)
                .build();
        MvcResult stRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(studentReg)))
                .andExpect(status().isCreated())
                .andReturn();
        String studentToken = objectMapper.readTree(stRes.getResponse().getContentAsString()).path("token").asText();

        // 4. Student joins session
        JoinSessionRequest joinReq = JoinSessionRequest.builder()
                .joinCode(joinCode)
                .displayName("Student Results")
                .build();
        MvcResult jRes = mockMvc.perform(post("/api/v1/sessions/join")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(joinReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String participantToken = objectMapper.readTree(jRes.getResponse().getContentAsString()).path("participantToken").asText();

        // 5. Student fetches results with BOTH Authorization: Bearer <studentToken> AND X-Participant-Token
        // Previously, this threw: Teacher role required to manage session ...
        // Now, it should return 200 OK!
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/activities/" + activityId + "/results")
                        .header("Authorization", "Bearer " + studentToken)
                        .header("X-Participant-Token", participantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(activityId))
                .andExpect(jsonPath("$.resultsVisible").value(true));
    }
}
