package com.classpulse.backend.service;

import com.classpulse.backend.config.ActivityConfigValidator;
import com.classpulse.backend.dto.ActivityOptionDto;
import com.classpulse.backend.dto.ActivityResponse;
import com.classpulse.backend.dto.CreateActivityRequest;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.ResourceNotFoundException;
import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityConfig;
import com.classpulse.backend.model.ActivityOption;
import com.classpulse.backend.model.ActivityStatus;
import com.classpulse.backend.model.Session;
import com.classpulse.backend.model.SessionStatus;
import com.classpulse.backend.repository.ActivityOptionRepository;
import com.classpulse.backend.repository.ActivityRepository;
import com.classpulse.backend.repository.SessionRepository;
import com.classpulse.backend.security.ParticipantSecurityService;
import com.classpulse.backend.security.TeacherSecurityService;
import com.classpulse.backend.websocket.WebSocketEventPublisher;
import com.classpulse.backend.websocket.WebSocketEventType;
import com.classpulse.backend.model.ActivityConfig;
import com.classpulse.backend.websocket.WebSocketEventType;

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
    private final WebSocketEventPublisher eventPublisher;

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

        int nextOrder = activityRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size();
        int orderIndex = request.getOrderIndex() != null ? request.getOrderIndex() : nextOrder;

        Activity activity = Activity.builder()
                .id(activityId)
                .session(session)
                .type(request.getType())
                .title(request.getTitle().trim())
                .status(ActivityStatus.DRAFT)
                .configJson(request.getConfigJson())
                .orderIndex(orderIndex)
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

        return activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(sessionId).stream()
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

        // Single active activity check: auto-close any previously active activity
        Optional<Activity> currentActive = activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE);
        if (currentActive.isPresent() && !currentActive.get().getId().equals(activityId)) {
            Activity previous = currentActive.get();
            previous.setStatus(ActivityStatus.CLOSED);
            previous.setActiveSessionId(null);
            previous.setClosedAt(clock.instant());
            activityRepository.saveAndFlush(previous);
            eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_CLOSED, sessionId, java.util.Map.of(
                    "activityId", previous.getId().toString()
            ));
        }

        Instant now = clock.instant();
        activity.setStatus(ActivityStatus.ACTIVE);
        activity.setActiveSessionId(sessionId);
        activity.setActivatedAt(now);

        try {
            Activity saved = activityRepository.saveAndFlush(activity);
            eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_ACTIVATED, sessionId, java.util.Map.of(
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
            eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_CLOSED, sessionId, java.util.Map.of(
                    "activityId", activity.getId().toString()
            ));
        }

        return mapToResponse(activity, true);
    }

    @Transactional
    public void deleteActivity(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Activity activity = getActivityOrThrow(activityId, sessionId);
        activityRepository.delete(activity);

        eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_DELETED, sessionId, java.util.Map.of(
                "activityId", activityId.toString()
        ));
    }

    @Transactional(readOnly = true)
    public Activity getActivityOrThrow(UUID activityId, UUID sessionId) {
        return activityRepository.findByIdAndSessionId(activityId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found with ID: " + activityId + " in session: " + sessionId));
    }

    @Transactional
    public ActivityResponse launchNextActivity(UUID sessionId, String teacherToken, String teacherKey) {
        Session session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BadRequestException("Cannot activate activity on inactive or expired session");
        }

        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        List<Activity> drafts = activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(sessionId).stream()
                .filter(a -> a.getStatus() == ActivityStatus.DRAFT)
                .toList();

        if (drafts.isEmpty()) {
            throw new BadRequestException("No queued unlaunched activities left to launch");
        }

        Activity nextActivity = drafts.get(0);
        return activateActivity(sessionId, nextActivity.getId(), teacherToken, teacherKey);
    }

    @Transactional
    public List<ActivityResponse> reorderActivities(UUID sessionId, List<UUID> activityIds, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        List<Activity> activities = activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(sessionId);
        java.util.Map<UUID, Activity> actMap = activities.stream().collect(java.util.stream.Collectors.toMap(Activity::getId, a -> a));

        int order = 0;
        for (UUID actId : activityIds) {
            Activity act = actMap.get(actId);
            if (act != null) {
                act.setOrderIndex(order++);
                activityRepository.save(act);
            }
        }
        return getActivitiesForSession(sessionId, teacherToken, teacherKey);
    }

    public ActivityResponse mapToResponse(Activity activity, boolean isTeacher) {
        boolean canSeeCorrect = isTeacher || activity.getStatus() == ActivityStatus.CLOSED;
        if (!canSeeCorrect && activity.getConfigJson() != null && !activity.getConfigJson().isBlank()) {
            try {
                ActivityConfig config =
                        objectMapper.readValue(activity.getConfigJson(), ActivityConfig.class);
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
                .orderIndex(activity.getOrderIndex())
                .createdAt(activity.getCreatedAt())
                .activatedAt(activity.getActivatedAt())
                .closedAt(activity.getClosedAt())
                .options(optionDtos)
                .build();
    }
}
