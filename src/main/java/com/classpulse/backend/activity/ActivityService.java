package com.classpulse.backend.activity;

import com.classpulse.backend.activity.config.ActivityConfigValidator;
import com.classpulse.backend.activity.dto.ActivityOptionDto;
import com.classpulse.backend.activity.dto.ActivityResponse;
import com.classpulse.backend.activity.dto.CreateActivityRequest;
import com.classpulse.backend.common.exception.BadRequestException;
import com.classpulse.backend.common.exception.ConflictException;
import com.classpulse.backend.common.exception.ResourceNotFoundException;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.session.Session;
import com.classpulse.backend.session.SessionRepository;
import com.classpulse.backend.session.SessionService;
import com.classpulse.backend.session.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final ActivityOptionRepository optionRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final TeacherSecurityService teacherSecurityService;
    private final ParticipantSecurityService participantSecurityService;
    private final ActivityConfigValidator configValidator;
    private final Clock clock;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final com.classpulse.backend.websocket.event.WebSocketEventPublisher eventPublisher;

    @Transactional
    public ActivityResponse createActivity(UUID sessionId, CreateActivityRequest request, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new BadRequestException("Cannot create activity in a closed session");
        }
        if (session.getStatus() == SessionStatus.EXPIRED) {
            throw new BadRequestException("Cannot create activity in an expired session");
        }

        configValidator.validate(request);

        UUID activityId = UUID.randomUUID();
        Instant now = clock.instant();

        Activity activity = Activity.builder()
                .id(activityId)
                .session(session)
                .type(request.getType())
                .title(request.getTitle().trim())
                .status(ActivityStatus.DRAFT)
                .configJson(request.getConfigJson())
                .createdAt(now)
                .options(new ArrayList<>())
                .build();

        if (request.getOptions() != null) {
            int order = 0;
            for (ActivityOptionDto optDto : request.getOptions()) {
                ActivityOption option = ActivityOption.builder()
                        .id(UUID.randomUUID())
                        .activity(activity)
                        .optionText(optDto.getOptionText().trim())
                        .isCorrect(Boolean.TRUE.equals(optDto.getIsCorrect()))
                        .displayOrder(order++)
                        .build();
                activity.getOptions().add(option);
            }
        }

        Activity saved = activityRepository.save(activity);
        return mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> getActivitiesForSession(UUID sessionId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        return activityRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(act -> mapToResponse(act, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public ActivityResponse getActivityById(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Activity activity = getActivityOrThrow(activityId, sessionId);
        return mapToResponse(activity, true);
    }

    @Transactional
    public Optional<ActivityResponse> getActiveActivityForParticipant(UUID sessionId, String participantToken) {
        participantSecurityService.authenticateParticipantForSession(participantToken, sessionId);

        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            return Optional.empty();
        }

        return activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE)
                .map(act -> mapToResponse(act, false));
    }

    @Transactional
    public ActivityResponse activateActivity(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        Session session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BadRequestException("Cannot activate activity on inactive or expired session");
        }

        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Activity activity = getActivityOrThrow(activityId, sessionId);

        if (activity.getStatus() == ActivityStatus.ACTIVE) {
            return mapToResponse(activity, true);
        }
        if (activity.getStatus() == ActivityStatus.CLOSED) {
            throw new BadRequestException("Cannot activate an activity that has already been closed");
        }

        // Single active activity check
        Optional<Activity> currentActive = activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE);
        if (currentActive.isPresent() && !currentActive.get().getId().equals(activityId)) {
            throw new ConflictException("Session already has an active activity: " + currentActive.get().getTitle());
        }

        Instant now = clock.instant();
        activity.setStatus(ActivityStatus.ACTIVE);
        activity.setActiveSessionId(sessionId);
        activity.setActivatedAt(now);

        try {
            Activity saved = activityRepository.saveAndFlush(activity);
            eventPublisher.publishSessionEvent(com.classpulse.backend.websocket.event.WebSocketEventType.ACTIVITY_ACTIVATED, sessionId, java.util.Map.of(
                    "activityId", activity.getId().toString(),
                    "title", activity.getTitle(),
                    "type", activity.getType().name()
            ));
            return mapToResponse(saved, true);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Concurrency violation: another activity was activated simultaneously");
        }
    }

    @Transactional
    public ActivityResponse closeActivity(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Activity activity = getActivityOrThrow(activityId, sessionId);

        if (activity.getStatus() != ActivityStatus.CLOSED) {
            activity.setStatus(ActivityStatus.CLOSED);
            activity.setActiveSessionId(null);
            activity.setClosedAt(clock.instant());
            activityRepository.save(activity);
            eventPublisher.publishSessionEvent(com.classpulse.backend.websocket.event.WebSocketEventType.ACTIVITY_CLOSED, sessionId, java.util.Map.of(
                    "activityId", activity.getId().toString()
            ));
        }

        return mapToResponse(activity, true);
    }

    @Transactional(readOnly = true)
    public Activity getActivityOrThrow(UUID activityId, UUID sessionId) {
        return activityRepository.findByIdAndSessionId(activityId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found with ID: " + activityId + " in session: " + sessionId));
    }

    public ActivityResponse mapToResponse(Activity activity, boolean isTeacher) {
        boolean canSeeCorrect = isTeacher || activity.getStatus() == ActivityStatus.CLOSED;
        if (!canSeeCorrect && activity.getConfigJson() != null && !activity.getConfigJson().isBlank()) {
            try {
                com.classpulse.backend.activity.config.ActivityConfig config =
                        objectMapper.readValue(activity.getConfigJson(), com.classpulse.backend.activity.config.ActivityConfig.class);
                if (config.isShowCorrectAnswer()) {
                    canSeeCorrect = true;
                }
            } catch (Exception ignored) {
            }
        }
        final boolean finalCanSeeCorrect = canSeeCorrect;

        List<ActivityOptionDto> optionDtos = activity.getOptions().stream()
                .map(opt -> ActivityOptionDto.builder()
                        .id(opt.getId())
                        .optionText(opt.getOptionText())
                        .isCorrect(finalCanSeeCorrect ? opt.isCorrect() : null)
                        .displayOrder(opt.getDisplayOrder())
                        .build())
                .toList();

        return ActivityResponse.builder()
                .id(activity.getId())
                .sessionId(activity.getSession().getId())
                .type(activity.getType())
                .title(activity.getTitle())
                .status(activity.getStatus())
                .configJson(activity.getConfigJson())
                .createdAt(activity.getCreatedAt())
                .activatedAt(activity.getActivatedAt())
                .closedAt(activity.getClosedAt())
                .options(optionDtos)
                .build();
    }
}
