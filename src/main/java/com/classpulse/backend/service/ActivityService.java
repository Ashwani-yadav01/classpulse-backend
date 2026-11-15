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

/**
 * Service managing activity authoring, lifecycle progression, presentation sequencing,
 * concurrency locks, and WebSocket notifications.
 * <p>
 * Key invariants:
 * <ul>
 *   <li>Only one activity per session may be {@code ACTIVE} at any time.</li>
 *   <li>Activating an activity auto-closes any previously running activity in that session.</li>
 *   <li>Concurrency is strictly controlled using pessimistic locking ({@code findByIdForUpdate}) and
 *       partial database unique constraints on {@code active_session_id}.</li>
 *   <li>Correct answer visibility is conditionally masked for student participants based on activity status
 *       and configuration flags.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ActivityService {

    /** Repository for managing Activity entities. */
    private final ActivityRepository activityRepository;

    /** Repository for managing ActivityOption entities. */
    private final ActivityOptionRepository optionRepository;

    /** Repository for Session locking and queries. */
    private final SessionRepository sessionRepository;

    /** Session domain service for state and expiry checks. */
    private final SessionService sessionService;

    /** Teacher security authorization service. */
    private final TeacherSecurityService teacherSecurityService;

    /** Participant security authentication service. */
    private final ParticipantSecurityService participantSecurityService;

    /** Domain validator enforcing option counts and string bounds. */
    private final ActivityConfigValidator configValidator;

    /** Centralized clock provider for deterministic timestamps. */
    private final Clock clock;

    /** JSON object mapper for deserializing activity config JSON. */
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** WebSocket event publisher for real-time STOMP broadcasts. */
    private final WebSocketEventPublisher eventPublisher;

    /**
     * Creates a new activity in DRAFT status belonging to the specified session.
     *
     * @param sessionId    session UUID
     * @param request      activity creation payload
     * @param teacherToken teacher token header
     * @param teacherKey   master key header
     * @return newly created activity details
     * @throws BadRequestException if session is closed or expired, or validation rules fail
     */
    @Transactional
    public ActivityResponse createActivity(UUID sessionId, CreateActivityRequest request, String teacherToken, String teacherKey) {
        // Step 1: Resolve parent session and verify not expired
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);

        // Step 2: Verify caller possesses teacher permissions for this session
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 3: Guard against adding activities to terminated sessions
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new BadRequestException("Cannot create activity in a closed session");
        }
        if (session.getStatus() == SessionStatus.EXPIRED) {
            throw new BadRequestException("Cannot create activity in an expired session");
        }

        // Step 4: Validate request against activity type rules (options count, title lengths)
        configValidator.validate(request);

        // Step 5: Establish IDs, timestamps, and sequential queue ordering
        UUID activityId = UUID.randomUUID();
        Instant now = clock.instant();
        int nextOrder = activityRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size();
        int orderIndex = request.getOrderIndex() != null ? request.getOrderIndex() : nextOrder;

        // Step 6: Assemble Activity entity in DRAFT status
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

        // Step 7: Cascade build associated options with ascending display order
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

        // Step 8: Persist activity and cascaded options to database
        Activity saved = activityRepository.save(activity);

        // Step 9: Return teacher-facing response (showing correct answers)
        return mapToResponse(saved, true);
    }

    /**
     * Retrieves all activities in a session ordered by presentation queue order index.
     *
     * @param sessionId    session UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return list of activities in queue sequence
     */
    @Transactional(readOnly = true)
    public List<ActivityResponse> getActivitiesForSession(UUID sessionId, String teacherToken, String teacherKey) {
        // Step 1: Verify session and teacher permissions
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Query activities ordered by orderIndex ascending, tie-broken by createdAt
        return activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(sessionId).stream()
                .map(act -> mapToResponse(act, true))
                .toList();
    }

    /**
     * Retrieves a single activity by its ID within a session for a teacher.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return activity details
     */
    @Transactional(readOnly = true)
    public ActivityResponse getActivityById(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        // Step 1: Verify teacher access
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Fetch activity entity
        Activity activity = getActivityOrThrow(activityId, sessionId);

        // Step 3: Map to response
        return mapToResponse(activity, true);
    }

    /**
     * Retrieves the currently ACTIVE activity for a student participant.
     *
     * @param sessionId        session UUID
     * @param participantToken participant token header
     * @return Optional containing active activity (with correct answers masked) or empty if none active
     */
    @Transactional
    public Optional<ActivityResponse> getActiveActivityForParticipant(UUID sessionId, String participantToken) {
        // Step 1: Authenticate participant token and confirm enrollment in this session
        participantSecurityService.authenticateParticipantForSession(participantToken, sessionId);

        // Step 2: Ensure session is still active and unexpired
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            return Optional.empty();
        }

        // Step 3: Query active activity for session; mask correct answers if not authorized to see them
        return activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE)
                .map(act -> mapToResponse(act, false));
    }

    /**
     * Activates an activity, opening it for student interaction.
     * Concurrently locks the parent session row and automatically closes any previously active activity.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID to activate
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return activated activity details
     * @throws BadRequestException if session is inactive or activity is already closed
     * @throws ConflictException   if another activity was activated simultaneously
     */
    @Transactional
    public ActivityResponse activateActivity(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        // Step 1: Lock parent session row pessimistically to prevent race conditions
        Session session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        // Step 2: Check session state
        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BadRequestException("Cannot activate activity on inactive or expired session");
        }

        // Step 3: Verify teacher permissions
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 4: Fetch target activity
        Activity activity = getActivityOrThrow(activityId, sessionId);

        // Idempotency: if already active, return immediately
        if (activity.getStatus() == ActivityStatus.ACTIVE) {
            return mapToResponse(activity, true);
        }
        // Guard against reopening closed activities
        if (activity.getStatus() == ActivityStatus.CLOSED) {
            throw new BadRequestException("Cannot activate an activity that has already been closed");
        }

        // Step 5: Enforce single-active-activity invariant by closing any currently active activity
        Optional<Activity> currentActive = activityRepository.findBySessionIdAndStatus(sessionId, ActivityStatus.ACTIVE);
        if (currentActive.isPresent() && !currentActive.get().getId().equals(activityId)) {
            Activity previous = currentActive.get();
            previous.setStatus(ActivityStatus.CLOSED);
            previous.setActiveSessionId(null);
            previous.setClosedAt(clock.instant());
            activityRepository.saveAndFlush(previous);

            // Broadcast ACTIVITY_CLOSED for previous activity
            eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_CLOSED, sessionId, java.util.Map.of(
                    "activityId", previous.getId().toString()
            ));
        }

        // Step 6: Mark target activity ACTIVE and set activatedAt timestamp and activeSessionId
        Instant now = clock.instant();
        activity.setStatus(ActivityStatus.ACTIVE);
        activity.setActiveSessionId(sessionId); // Satisfies unique constraint on active_session_id
        activity.setActivatedAt(now);

        try {
            // Step 7: Flush changes to trigger any database unique constraint collisions
            Activity saved = activityRepository.saveAndFlush(activity);

            // Step 8: Broadcast ACTIVITY_ACTIVATED across session WebSocket channel
            eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_ACTIVATED, sessionId, java.util.Map.of(
                    "activityId", activity.getId().toString(),
                    "title", activity.getTitle(),
                    "type", activity.getType().name()
            ));

            return mapToResponse(saved, true);
        } catch (DataIntegrityViolationException e) {
            // Concurrent activation caught by partial unique index
            throw new ConflictException("Concurrency violation: another activity was activated simultaneously");
        }
    }

    /**
     * Closes an activity, locking answers and setting status to CLOSED.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return updated activity details
     */
    @Transactional
    public ActivityResponse closeActivity(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        // Step 1: Verify teacher access
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Fetch activity
        Activity activity = getActivityOrThrow(activityId, sessionId);

        // Step 3: Transition to CLOSED if not already closed
        if (activity.getStatus() != ActivityStatus.CLOSED) {
            activity.setStatus(ActivityStatus.CLOSED);
            activity.setActiveSessionId(null); // Clear active session marker
            activity.setClosedAt(clock.instant());
            activityRepository.save(activity);

            // Step 4: Broadcast ACTIVITY_CLOSED event
            eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_CLOSED, sessionId, java.util.Map.of(
                    "activityId", activity.getId().toString()
            ));
        }

        return mapToResponse(activity, true);
    }

    /**
     * Deletes an activity and broadcasts ACTIVITY_DELETED.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     */
    @Transactional
    public void deleteActivity(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        // Step 1: Verify permissions
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Fetch and delete entity
        Activity activity = getActivityOrThrow(activityId, sessionId);
        activityRepository.delete(activity);

        // Step 3: Broadcast ACTIVITY_DELETED event
        eventPublisher.publishSessionEvent(WebSocketEventType.ACTIVITY_DELETED, sessionId, java.util.Map.of(
                "activityId", activityId.toString()
        ));
    }

    /**
     * Helper resolving an activity by ID and verifying it belongs to the expected session.
     *
     * @param activityId activity UUID
     * @param sessionId  session UUID
     * @return persistent Activity entity
     */
    @Transactional(readOnly = true)
    public Activity getActivityOrThrow(UUID activityId, UUID sessionId) {
        return activityRepository.findByIdAndSessionId(activityId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found with ID: " + activityId + " in session: " + sessionId));
    }

    /**
     * Advances to the next queued activity in DRAFT status and activates it.
     *
     * @param sessionId    session UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return newly activated activity
     * @throws BadRequestException if no unlaunched activities remain in queue
     */
    @Transactional
    public ActivityResponse launchNextActivity(UUID sessionId, String teacherToken, String teacherKey) {
        // Step 1: Concurrently lock session row
        Session session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        // Step 2: Ensure session is active
        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BadRequestException("Cannot activate activity on inactive or expired session");
        }

        // Step 3: Verify teacher permissions
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 4: Find all draft activities ordered by presentation sequence
        List<Activity> drafts = activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(sessionId).stream()
                .filter(a -> a.getStatus() == ActivityStatus.DRAFT)
                .toList();

        // Step 5: Guard against empty queue
        if (drafts.isEmpty()) {
            throw new BadRequestException("No queued unlaunched activities left to launch");
        }

        // Step 6: Select first draft activity and activate it
        Activity nextActivity = drafts.get(0);
        return activateActivity(sessionId, nextActivity.getId(), teacherToken, teacherKey);
    }

    /**
     * Updates the presentation order index of activities in the classroom sequence.
     *
     * @param sessionId    session UUID
     * @param activityIds  ordered list of activity UUIDs
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return updated list of activities
     */
    @Transactional
    public List<ActivityResponse> reorderActivities(UUID sessionId, List<UUID> activityIds, String teacherToken, String teacherKey) {
        // Step 1: Verify teacher permissions
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Map activities by ID for index updates
        List<Activity> activities = activityRepository.findBySessionIdOrderByOrderIndexAscCreatedAtAsc(sessionId);
        java.util.Map<UUID, Activity> actMap = activities.stream().collect(java.util.stream.Collectors.toMap(Activity::getId, a -> a));

        // Step 3: Assign new sequential orderIndex values
        int order = 0;
        for (UUID actId : activityIds) {
            Activity act = actMap.get(actId);
            if (act != null) {
                act.setOrderIndex(order++);
                activityRepository.save(act);
            }
        }

        // Step 4: Return reordered list
        return getActivitiesForSession(sessionId, teacherToken, teacherKey);
    }

    /**
     * Maps an {@link Activity} entity to {@link ActivityResponse}, conditionally masking correct answers.
     *
     * @param activity  the Activity entity
     * @param isTeacher true if caller is teacher; false if student participant
     * @return transformed ActivityResponse
     */
    public ActivityResponse mapToResponse(Activity activity, boolean isTeacher) {
        // Teachers always see correct answer flags. Participants only see them if closed or config explicitly allows
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

        // Map options, setting isCorrect to null if student cannot see correct answers
        List<ActivityOptionDto> optionDtos = activity.getOptions().stream()
                .map(opt -> ActivityOptionDto.builder()
                        .id(opt.getId())
                        .optionText(opt.getOptionText())
                        .isCorrect(finalCanSeeCorrect ? opt.isCorrect() : null)
                        .displayOrder(opt.getDisplayOrder())
                        .build())
                .toList();

        // Assemble ActivityResponse DTO
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
