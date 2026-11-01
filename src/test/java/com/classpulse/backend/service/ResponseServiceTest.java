package com.classpulse.backend.service;

import com.classpulse.backend.dto.ActivityResultResponse;
import com.classpulse.backend.dto.ActivityVisibilityRequest;
import com.classpulse.backend.dto.SubmitResponseRequest;
import com.classpulse.backend.dto.SubmitResponseResponse;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityOption;
import com.classpulse.backend.model.ActivityStatus;
import com.classpulse.backend.model.ActivityType;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.Response;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.repository.ActivityOptionRepository;
import com.classpulse.backend.repository.ActivityRepository;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.repository.PrioritizationRankRepository;
import com.classpulse.backend.repository.RandomPickerSelectionRepository;
import com.classpulse.backend.repository.ResponseRepository;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;
import com.classpulse.backend.websocket.WebSocketEventPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseServiceTest {

    @Mock
    private ResponseRepository responseRepository;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private ActivityOptionRepository optionRepository;

    @Mock
    private ActivityService activityService;

    @Mock
    private SessionService sessionService;

    private TeacherSecurityService teacherSecurityService;

    @Mock
    private ParticipantSecurityService participantSecurityService;

    @Mock
    private PrioritizationRankRepository rankRepository;

    @Mock
    private RandomPickerSelectionRepository randomPickerRepository;

    @Mock
    private ParticipantRepository participantRepository;

    @Mock
    private WebSocketEventPublisher eventPublisher;

    private ResultAggregationService resultAggregationService;
    private Clock fixedClock;
    private ObjectMapper objectMapper;
    private ResponseService responseService;

    private final Instant now = Instant.parse("2026-10-25T12:00:00Z");
    private final String teacherToken = "teacher-secret-token";
    private final String participantToken = "participant-secret-token";

    private UUID sessionId;
    private UUID activityId;
    private UUID option1Id;
    private UUID option2Id;
    private Session session;
    private Participant participant;
    private Activity activity;
    private ActivityOption option1;
    private ActivityOption option2;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        teacherSecurityService = new TeacherSecurityService("master-key");
        resultAggregationService = new ResultAggregationService(
                responseRepository,
                optionRepository,
                rankRepository,
                randomPickerRepository,
                participantRepository,
                objectMapper
        );

        responseService = new ResponseService(
                responseRepository,
                activityRepository,
                optionRepository,
                rankRepository,
                randomPickerRepository,
                participantRepository,
                activityService,
                sessionService,
                teacherSecurityService,
                participantSecurityService,
                resultAggregationService,
                eventPublisher,
                fixedClock,
                objectMapper
        );

        sessionId = UUID.randomUUID();
        activityId = UUID.randomUUID();
        option1Id = UUID.randomUUID();
        option2Id = UUID.randomUUID();

        session = Session.builder()
                .id(sessionId)
                .title("Class 101")
                .joinCode("C10100")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(CryptoUtils.hashToken(teacherToken))
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        participant = Participant.builder()
                .id(UUID.randomUUID())
                .session(session)
                .displayName("Alice")
                .tokenHash(CryptoUtils.hashToken(participantToken))
                .isRevoked(false)
                .joinedAt(now)
                .lastSeenAt(now)
                .build();

        option1 = ActivityOption.builder()
                .id(option1Id)
                .optionText("Option A")
                .isCorrect(true)
                .displayOrder(0)
                .build();

        option2 = ActivityOption.builder()
                .id(option2Id)
                .optionText("Option B")
                .isCorrect(false)
                .displayOrder(1)
                .build();

        activity = Activity.builder()
                .id(activityId)
                .session(session)
                .type(ActivityType.MCQ)
                .title("Question 1")
                .status(ActivityStatus.ACTIVE)
                .configJson("{\"showCorrectAnswer\":false,\"allowRevision\":false,\"resultsVisible\":false}")
                .options(List.of(option1, option2))
                .createdAt(now)
                .build();

        option1.setActivity(activity);
        option2.setActivity(activity);
    }

    @Test
    void submitResponse_mcqSuccess_whenFirstSubmission() {
        when(participantSecurityService.authenticateParticipantForSession(participantToken, sessionId)).thenReturn(participant);
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(optionRepository.findByIdAndActivityId(option1Id, activityId)).thenReturn(Optional.of(option1));
        when(responseRepository.findByActivityIdAndParticipantId(activityId, participant.getId())).thenReturn(List.of());
        when(responseRepository.saveAndFlush(any(Response.class))).thenAnswer(i -> i.getArgument(0));

        SubmitResponseRequest req = SubmitResponseRequest.builder().selectedOptionId(option1Id).build();
        SubmitResponseResponse response = responseService.submitResponse(sessionId, activityId, req, participantToken);

        assertThat(response.getActivityId()).isEqualTo(activityId);
        assertThat(response.getSelectedOptionId()).isEqualTo(option1Id);
        assertThat(response.isRevised()).isFalse();
        assertThat(response.getSubmittedAt()).isEqualTo(now);
    }

    @Test
    void submitResponse_mcqConflict_whenRevisionDisabledAndAlreadyVoted() {
        when(participantSecurityService.authenticateParticipantForSession(participantToken, sessionId)).thenReturn(participant);
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(optionRepository.findByIdAndActivityId(option2Id, activityId)).thenReturn(Optional.of(option2));

        Response existing = Response.builder()
                .id(UUID.randomUUID())
                .activity(activity)
                .participant(participant)
                .selectedOption(option1)
                .submittedAt(now.minusSeconds(10))
                .updatedAt(now.minusSeconds(10))
                .build();

        when(responseRepository.findByActivityIdAndParticipantId(activityId, participant.getId())).thenReturn(List.of(existing));

        SubmitResponseRequest req = SubmitResponseRequest.builder().selectedOptionId(option2Id).build();
        assertThatThrownBy(() -> responseService.submitResponse(sessionId, activityId, req, participantToken))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("revision is not allowed");
    }

    @Test
    void submitResponse_pollRevision_shouldUpdateExistingVote() {
        activity.setType(ActivityType.POLL);
        activity.setConfigJson("{\"allowVoteRevision\":true}");

        when(participantSecurityService.authenticateParticipantForSession(participantToken, sessionId)).thenReturn(participant);
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(optionRepository.findByIdAndActivityId(option2Id, activityId)).thenReturn(Optional.of(option2));

        Response existing = Response.builder()
                .id(UUID.randomUUID())
                .activity(activity)
                .participant(participant)
                .selectedOption(option1)
                .submittedAt(now.minusSeconds(30))
                .updatedAt(now.minusSeconds(30))
                .build();

        when(responseRepository.findByActivityIdAndParticipantId(activityId, participant.getId())).thenReturn(List.of(existing));
        when(responseRepository.save(any(Response.class))).thenAnswer(i -> i.getArgument(0));

        SubmitResponseRequest req = SubmitResponseRequest.builder().selectedOptionId(option2Id).build();
        SubmitResponseResponse response = responseService.submitResponse(sessionId, activityId, req, participantToken);

        assertThat(response.isRevised()).isTrue();
        assertThat(response.getSelectedOptionId()).isEqualTo(option2Id);
        assertThat(existing.getSelectedOption()).isEqualTo(option2);
        assertThat(existing.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void submitResponse_whenActivityNotActive_shouldThrowBadRequest() {
        activity.setStatus(ActivityStatus.DRAFT);

        when(participantSecurityService.authenticateParticipantForSession(participantToken, sessionId)).thenReturn(participant);
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);

        SubmitResponseRequest req = SubmitResponseRequest.builder().selectedOptionId(option1Id).build();
        assertThatThrownBy(() -> responseService.submitResponse(sessionId, activityId, req, participantToken))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("activity that is not active");
    }

    @Test
    void submitResponse_whenOptionDoesNotBelongToActivity_shouldThrowBadRequest() {
        when(participantSecurityService.authenticateParticipantForSession(participantToken, sessionId)).thenReturn(participant);
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(optionRepository.findByIdAndActivityId(option1Id, activityId)).thenReturn(Optional.empty());

        SubmitResponseRequest req = SubmitResponseRequest.builder().selectedOptionId(option1Id).build();
        assertThatThrownBy(() -> responseService.submitResponse(sessionId, activityId, req, participantToken))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Selected option does not belong to this activity");
    }

    @Test
    void getResults_byTeacher_shouldAlwaysSucceedAndIncludeCorrectAnswer() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(optionRepository.findByActivityIdOrderByDisplayOrderAsc(activityId)).thenReturn(List.of(option1, option2));

        Response r1 = Response.builder().id(UUID.randomUUID()).activity(activity).participant(participant).selectedOption(option1).build();
        when(responseRepository.findByActivityId(activityId)).thenReturn(List.of(r1));

        ActivityResultResponse results = responseService.getResults(sessionId, activityId, teacherToken, null, null);

        assertThat(results.getTotalVotes()).isEqualTo(1);
        assertThat(results.getOptions().get(0).getCount()).isEqualTo(1);
        assertThat(results.getOptions().get(0).getPercentage()).isEqualTo(100.0);
        assertThat(results.getOptions().get(0).getIsCorrect()).isTrue();
        assertThat(results.getOptions().get(1).getCount()).isEqualTo(0);
        assertThat(results.getOptions().get(1).getPercentage()).isEqualTo(0.0);
        assertThat(results.getOptions().get(1).getIsCorrect()).isFalse();
    }

    @Test
    void getResults_byParticipant_whenHidden_shouldThrowForbidden() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);

        assertThatThrownBy(() -> responseService.getResults(sessionId, activityId, null, null, participantToken))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Results are not yet visible");
    }

    @Test
    void getResults_byParticipant_whenRevealed_shouldHideCorrectAnswerUnlessExplicitlyShown() {
        activity.setConfigJson("{\"resultsVisible\":true,\"showCorrectAnswer\":false}");

        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(optionRepository.findByActivityIdOrderByDisplayOrderAsc(activityId)).thenReturn(List.of(option1, option2));
        when(responseRepository.findByActivityId(activityId)).thenReturn(List.of());

        ActivityResultResponse results = responseService.getResults(sessionId, activityId, null, null, participantToken);

        assertThat(results.isResultsVisible()).isTrue();
        assertThat(results.getOptions().get(0).getIsCorrect()).isNull();
    }

    @Test
    void updateVisibility_byTeacher_shouldUpdateConfig() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityService.getActivityOrThrow(activityId, sessionId)).thenReturn(activity);
        when(activityRepository.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));

        ActivityVisibilityRequest req = ActivityVisibilityRequest.builder()
                .resultsVisible(true)
                .showCorrectAnswer(true)
                .build();

        responseService.updateVisibility(sessionId, activityId, req, teacherToken, null);

        assertThat(activity.getConfigJson()).contains("\"resultsVisible\":true");
        assertThat(activity.getConfigJson()).contains("\"showCorrectAnswer\":true");
    }
}
