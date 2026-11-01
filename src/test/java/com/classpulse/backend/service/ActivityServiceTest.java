package com.classpulse.backend.service;

import com.classpulse.backend.config.ActivityConfigValidator;
import com.classpulse.backend.dto.ActivityOptionDto;
import com.classpulse.backend.dto.ActivityResponse;
import com.classpulse.backend.dto.CreateActivityRequest;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityStatus;
import com.classpulse.backend.model.ActivityType;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.repository.ActivityOptionRepository;
import com.classpulse.backend.repository.ActivityRepository;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.util.CryptoUtils;
import com.classpulse.backend.websocket.WebSocketEventPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private ActivityOptionRepository optionRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionService sessionService;

    @Mock
    private ParticipantSecurityService participantSecurityService;

    @Mock
    private WebSocketEventPublisher eventPublisher;

    private TeacherSecurityService teacherSecurityService;
    private ActivityConfigValidator configValidator;
    private Clock fixedClock;
    private ActivityService activityService;

    private final Instant now = Instant.parse("2026-10-25T12:00:00Z");
    private final String teacherToken = "teacher-secret-token";
    private final String teacherTokenHash = CryptoUtils.hashToken(teacherToken);
    private UUID sessionId;
    private Session session;

    @BeforeEach
    void setUp() {
        teacherSecurityService = new TeacherSecurityService("master-key");
        configValidator = new ActivityConfigValidator();
        fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        sessionId = UUID.randomUUID();

        session = Session.builder()
                .id(sessionId)
                .title("Distributed Systems")
                .joinCode("DIST12")
                .status(SessionStatus.ACTIVE)
                .teacherTokenHash(teacherTokenHash)
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();

        activityService = new ActivityService(
                activityRepository,
                optionRepository,
                sessionRepository,
                sessionService,
                teacherSecurityService,
                participantSecurityService,
                configValidator,
                fixedClock,
                new tools.jackson.databind.ObjectMapper(),
                eventPublisher
        );
    }

    @Test
    void createDraftActivity_shouldSucceedForSessionOwner() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityRepository.save(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateActivityRequest request = CreateActivityRequest.builder()
                .type(ActivityType.MCQ)
                .title("Which consensus algorithm is leader-based?")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Raft").isCorrect(true).build(),
                        ActivityOptionDto.builder().optionText("Paxos").isCorrect(false).build()
                ))
                .build();

        ActivityResponse response = activityService.createActivity(sessionId, request, teacherToken, null);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getTitle()).isEqualTo("Which consensus algorithm is leader-based?");
        assertThat(response.getStatus()).isEqualTo(ActivityStatus.DRAFT);
        assertThat(response.getOptions()).hasSize(2);
        assertThat(response.getOptions().get(0).getOptionText()).isEqualTo("Raft");
    }

    @Test
    void createActivity_withInvalidConfig_mcqWithLessThanTwoOptions_shouldThrowBadRequest() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);

        CreateActivityRequest request = CreateActivityRequest.builder()
                .type(ActivityType.MCQ)
                .title("Incomplete Question")
                .options(List.of(ActivityOptionDto.builder().optionText("Single Option").build()))
                .build();

        assertThatThrownBy(() -> activityService.createActivity(sessionId, request, teacherToken, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must have at least 2 options");
    }

    @Test
    void createActivity_withInvalidConfig_pollWithBlankOption_shouldThrowBadRequest() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);

        CreateActivityRequest request = CreateActivityRequest.builder()
                .type(ActivityType.POLL)
                .title("Invalid Poll")
                .options(List.of(
                        ActivityOptionDto.builder().optionText("Option 1").build(),
                        ActivityOptionDto.builder().optionText("   ").build()
                ))
                .build();

        assertThatThrownBy(() -> activityService.createActivity(sessionId, request, teacherToken, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("text cannot be blank");
    }

    @Test
    void createActivity_withOwnershipViolation_shouldThrowForbidden() {
        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);

        CreateActivityRequest request = CreateActivityRequest.builder()
                .type(ActivityType.WORD_CLOUD)
                .title("One word summary")
                .build();

        assertThatThrownBy(() -> activityService.createActivity(sessionId, request, "wrong-teacher-token", null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void activateActivity_shouldTransitionDraftToActive() {
        UUID activityId = UUID.randomUUID();
        Activity activity = Activity.builder()
                .id(activityId)
                .session(session)
                .type(ActivityType.POLL)
                .title("Do you understand?")
                .status(ActivityStatus.DRAFT)
                .options(new ArrayList<>())
                .build();

        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(activityRepository.findByIdAndSessionId(activityId, sessionId)).thenReturn(Optional.of(activity));
        when(activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE)).thenReturn(Optional.empty());
        when(activityRepository.saveAndFlush(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActivityResponse response = activityService.activateActivity(sessionId, activityId, teacherToken, null);

        assertThat(response.getStatus()).isEqualTo(ActivityStatus.ACTIVE);
        assertThat(response.getActivatedAt()).isEqualTo(now);
        assertThat(activity.getActiveSessionId()).isEqualTo(sessionId);
    }

    @Test
    void activateActivity_whenAnotherActivityAlreadyActive_shouldAutoClosePreviousAndActivateNew() {
        UUID activity1Id = UUID.randomUUID();
        UUID activity2Id = UUID.randomUUID();

        Activity activeActivity = Activity.builder()
                .id(activity1Id)
                .session(session)
                .type(ActivityType.MCQ)
                .title("First Question")
                .status(ActivityStatus.ACTIVE)
                .options(new ArrayList<>())
                .build();

        Activity draftActivity = Activity.builder()
                .id(activity2Id)
                .session(session)
                .type(ActivityType.POLL)
                .title("Second Question")
                .status(ActivityStatus.DRAFT)
                .options(new ArrayList<>())
                .build();

        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(activityRepository.findByIdAndSessionId(activity2Id, sessionId)).thenReturn(Optional.of(draftActivity));
        when(activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE)).thenReturn(Optional.of(activeActivity));
        when(activityRepository.saveAndFlush(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActivityResponse response = activityService.activateActivity(sessionId, activity2Id, teacherToken, null);

        assertThat(response.getStatus()).isEqualTo(ActivityStatus.ACTIVE);
        assertThat(activeActivity.getStatus()).isEqualTo(ActivityStatus.CLOSED);
        assertThat(activeActivity.getActiveSessionId()).isNull();
    }

    @Test
    void activateActivity_whenActivityAlreadyClosed_shouldThrowBadRequest() {
        UUID activityId = UUID.randomUUID();
        Activity closedActivity = Activity.builder()
                .id(activityId)
                .session(session)
                .type(ActivityType.MCQ)
                .title("Closed Question")
                .status(ActivityStatus.CLOSED)
                .options(new ArrayList<>())
                .build();

        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(activityRepository.findByIdAndSessionId(activityId, sessionId)).thenReturn(Optional.of(closedActivity));

        assertThatThrownBy(() -> activityService.activateActivity(sessionId, activityId, teacherToken, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot activate an activity that has already been closed");
    }

    @Test
    void closeActivity_shouldTransitionActiveToClosed() {
        UUID activityId = UUID.randomUUID();
        Activity activeActivity = Activity.builder()
                .id(activityId)
                .session(session)
                .type(ActivityType.POLL)
                .title("Active Question")
                .status(ActivityStatus.ACTIVE)
                .activeSessionId(sessionId)
                .options(new ArrayList<>())
                .build();

        when(sessionService.getSessionOrThrow(sessionId)).thenReturn(session);
        when(activityRepository.findByIdAndSessionId(activityId, sessionId)).thenReturn(Optional.of(activeActivity));

        ActivityResponse response = activityService.closeActivity(sessionId, activityId, teacherToken, null);

        assertThat(response.getStatus()).isEqualTo(ActivityStatus.CLOSED);
        assertThat(activeActivity.getActiveSessionId()).isNull();
        assertThat(activeActivity.getClosedAt()).isEqualTo(now);
    }
}
