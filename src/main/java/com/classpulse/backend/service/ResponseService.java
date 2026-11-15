package com.classpulse.backend.service;

import com.classpulse.backend.dto.ActivityResponse;
import com.classpulse.backend.dto.ActivityResultResponse;
import com.classpulse.backend.dto.ActivityVisibilityRequest;
import com.classpulse.backend.dto.RandomPickerResponse;
import com.classpulse.backend.dto.SubmitResponseRequest;
import com.classpulse.backend.dto.SubmitResponseResponse;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityConfig;
import com.classpulse.backend.model.ActivityOption;
import com.classpulse.backend.model.ActivityStatus;
import com.classpulse.backend.model.ActivityType;
import com.classpulse.backend.model.Participant;
import com.classpulse.backend.model.PrioritizationRank;
import com.classpulse.backend.model.RandomPickerSelection;
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
import com.classpulse.backend.websocket.WebSocketEventPublisher;
import com.classpulse.backend.websocket.WebSocketEventType;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service managing student response submissions, revisions, random student draws,
 * visibility updates, and result aggregations.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Validating submission payloads according to the active activity type (MCQ, Poll, Word Cloud, etc.).</li>
 *   <li>Enforcing revision policy (allowing updates vs rejecting duplicates based on configuration).</li>
 *   <li>Cascading ordered rankings into {@link PrioritizationRank} records for ranked choice activities.</li>
 *   <li>Performing cryptographically secure random draws without replacement for Random Student Picker.</li>
 *   <li>Broadcasting response submission and result update events across WebSocket STOMP destinations.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ResponseService {

    /** Repository managing persistent Response entities. */
    private final ResponseRepository responseRepository;

    /** Repository for Activity entities. */
    private final ActivityRepository activityRepository;

    /** Repository for ActivityOption entities. */
    private final ActivityOptionRepository optionRepository;

    /** Repository for PrioritizationRank records. */
    private final PrioritizationRankRepository rankRepository;

    /** Repository for RandomPickerSelection audit records. */
    private final RandomPickerSelectionRepository randomPickerRepository;

    /** Repository for Participant lookups and rosters. */
    private final ParticipantRepository participantRepository;

    /** Service for activity queries and mappings. */
    private final ActivityService activityService;

    /** Service for session lifecycle and expiry verification. */
    private final SessionService sessionService;

    /** Security service for teacher credentials. */
    private final TeacherSecurityService teacherSecurityService;

    /** Security service for participant token authentication. */
    private final ParticipantSecurityService participantSecurityService;

    /** Domain service computing charts, word frequencies, and rankings. */
    private final ResultAggregationService resultAggregationService;

    /** WebSocket event publisher for real-time notifications. */
    private final WebSocketEventPublisher eventPublisher;

    /** Centralized clock provider for deterministic timestamps. */
    private final Clock clock;

    /** JSON object mapper for configuration parsing. */
    private final ObjectMapper objectMapper;

    /** Cryptographically secure RNG for unbiased random student selection. */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Submits or revises a participant's response to an active activity.
     *
     * @param sessionId        parent session UUID
     * @param activityId       target activity UUID
     * @param request          submission payload containing option ID, text, or ranked option IDs
     * @param participantToken participant authentication token header
     * @return confirmation payload indicating response ID and revision status
     * @throws BadRequestException if session/activity is inactive, or payload violates rules
     * @throws ConflictException   if duplicate submission is attempted when revisions are disabled
     */
    @Transactional
    public SubmitResponseResponse submitResponse(UUID sessionId, UUID activityId, SubmitResponseRequest request, String participantToken) {
        // Step 1: Authenticate participant token and ensure student is enrolled in this specific session
        Participant participant = participantSecurityService.authenticateParticipantForSession(participantToken, sessionId);

        // Step 2: Ensure session is ACTIVE and unexpired
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BadRequestException("Cannot submit response to an inactive or expired session");
        }

        // Step 3: Ensure targeted activity is currently ACTIVE
        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);
        if (activity.getStatus() != ActivityStatus.ACTIVE) {
            throw new BadRequestException("Cannot submit response to an activity that is not active");
        }

        // Random student picker draws students server-side and does not accept participant submissions
        if (activity.getType() == ActivityType.RANDOM_PICKER) {
            throw new BadRequestException("Random Student Picker does not accept participant response submissions");
        }

        // Step 4: Parse activity configuration and determine revision policy
        ActivityConfig config = resultAggregationService.parseConfig(activity.getConfigJson());
        boolean allowRevision = (activity.getType() == ActivityType.POLL) ? config.isAllowVoteRevision() : config.isAllowRevision();

        // Step 5: Validate and extract submission payload based on activity type
        ActivityOption selectedOption = null;
        String textResponse = null;
        String normalizedText = null;
        List<ActivityOption> prioritizationOptions = null;

        switch (activity.getType()) {
            case MCQ, POLL -> {
                // Ensure option ID is supplied
                if (request.getSelectedOptionId() == null) {
                    throw new BadRequestException("selectedOptionId is required for " + activity.getType());
                }
                // Verify option exists and belongs to this activity
                selectedOption = optionRepository.findByIdAndActivityId(request.getSelectedOptionId(), activityId)
                        .orElseThrow(() -> new BadRequestException("Selected option does not belong to this activity"));
            }
            case WORD_CLOUD -> {
                // Verify non-blank text
                if (request.getTextResponse() == null || request.getTextResponse().trim().isBlank()) {
                    throw new BadRequestException("Word or phrase cannot be blank");
                }
                textResponse = request.getTextResponse().trim();
                // Enforce max word length limit
                int maxLen = config.getMaxWordLength() != null ? config.getMaxWordLength() : 50;
                if (textResponse.length() > maxLen) {
                    throw new BadRequestException("Word or phrase exceeds maximum length of " + maxLen + " characters");
                }
                // Compute lowercase normalized text for clustering
                normalizedText = textResponse.toLowerCase();
            }
            case OPEN_QUESTION, BRAINSTORMING -> {
                // Verify non-blank text
                if (request.getTextResponse() == null || request.getTextResponse().trim().isBlank()) {
                    throw new BadRequestException("Text response cannot be blank");
                }
                textResponse = request.getTextResponse().trim();
                // Enforce max text length limit
                int maxLen = config.getMaxTextLength() != null ? config.getMaxTextLength() : 1000;
                if (textResponse.length() > maxLen) {
                    throw new BadRequestException("Text response exceeds maximum length of " + maxLen + " characters");
                }
                normalizedText = textResponse.toLowerCase();
            }
            case PRIORITIZATION -> {
                // Ensure non-empty ranked list
                if (request.getOrderedOptionIds() == null || request.getOrderedOptionIds().isEmpty()) {
                    throw new BadRequestException("orderedOptionIds list is required for Prioritization");
                }
                // Fetch all configured options
                List<ActivityOption> existingOptions = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activityId);
                if (request.getOrderedOptionIds().size() != existingOptions.size()) {
                    throw new BadRequestException("Ordered option list must contain exactly " + existingOptions.size() + " options");
                }

                // Verify set equality and absence of duplicates
                Set<UUID> existingOptionIds = existingOptions.stream().map(ActivityOption::getId).collect(Collectors.toSet());
                Set<UUID> submittedOptionIds = new HashSet<>(request.getOrderedOptionIds());
                if (submittedOptionIds.size() != request.getOrderedOptionIds().size()) {
                    throw new BadRequestException("Duplicate option IDs found in prioritization ranking");
                }
                if (!existingOptionIds.equals(submittedOptionIds)) {
                    throw new BadRequestException("Prioritization rankings must contain only valid options belonging to this activity");
                }

                // Map option IDs to persistent options in requested rank sequence
                Map<UUID, ActivityOption> optionMap = existingOptions.stream().collect(Collectors.toMap(ActivityOption::getId, opt -> opt));
                prioritizationOptions = request.getOrderedOptionIds().stream().map(optionMap::get).toList();
            }
            case RANDOM_PICKER -> throw new BadRequestException("Random picker does not accept responses");
        }

        // Step 6: Enforce revision rules for single-submission vs multi-submission activities
        boolean isMultiSubmissionActivity = (activity.getType() == ActivityType.WORD_CLOUD || activity.getType() == ActivityType.BRAINSTORMING);
        List<Response> existingResponses = responseRepository.findByActivityIdAndParticipantId(activityId, participant.getId());
        Instant now = clock.instant();

        // Handle revision of existing single-submission answer
        if (!isMultiSubmissionActivity && !existingResponses.isEmpty()) {
            if (!allowRevision) {
                throw new ConflictException("Participant has already submitted a response and revision is not allowed");
            }

            Response existing = existingResponses.get(0);
            existing.setSelectedOption(selectedOption);
            existing.setTextResponse(textResponse);
            existing.setNormalizedText(normalizedText);
            existing.setUpdatedAt(now);

            // Re-create rankings for Prioritization
            if (activity.getType() == ActivityType.PRIORITIZATION) {
                rankRepository.deleteByResponseId(existing.getId());
                rankRepository.flush();
                savePrioritizationRanks(existing, prioritizationOptions);
            }

            Response saved = responseRepository.save(existing);
            // Broadcast update event
            publishResponseEvents(activity, true);

            return SubmitResponseResponse.builder()
                    .responseId(saved.getId())
                    .activityId(activityId)
                    .selectedOptionId(selectedOption != null ? selectedOption.getId() : null)
                    .textResponse(textResponse)
                    .orderedOptionIds(request.getOrderedOptionIds())
                    .revised(true)
                    .submittedAt(now)
                    .build();
        }

        // Step 7: Create brand new Response entity
        Response newResponse = Response.builder()
                .id(UUID.randomUUID())
                .activity(activity)
                .participant(participant)
                .selectedOption(selectedOption)
                .textResponse(textResponse)
                .normalizedText(normalizedText)
                .submittedAt(now)
                .updatedAt(now)
                .build();

        try {
            // Save and flush response
            Response saved = responseRepository.saveAndFlush(newResponse);

            // Save prioritization ranks if applicable
            if (activity.getType() == ActivityType.PRIORITIZATION) {
                savePrioritizationRanks(saved, prioritizationOptions);
            }

            // Broadcast real-time events
            publishResponseEvents(activity, false);

            return SubmitResponseResponse.builder()
                    .responseId(saved.getId())
                    .activityId(activityId)
                    .selectedOptionId(selectedOption != null ? selectedOption.getId() : null)
                    .textResponse(textResponse)
                    .orderedOptionIds(request.getOrderedOptionIds())
                    .revised(false)
                    .submittedAt(now)
                    .build();
        } catch (DataIntegrityViolationException e) {
            // Handle concurrent submission race conditions
            if (allowRevision && !isMultiSubmissionActivity) {
                Response concurrent = responseRepository.findFirstByActivityIdAndParticipantIdOrderBySubmittedAtDesc(activityId, participant.getId())
                        .orElseThrow(() -> new ConflictException("Concurrent response submission conflict"));
                concurrent.setSelectedOption(selectedOption);
                concurrent.setTextResponse(textResponse);
                concurrent.setNormalizedText(normalizedText);
                concurrent.setUpdatedAt(now);
                if (activity.getType() == ActivityType.PRIORITIZATION) {
                    rankRepository.deleteByResponseId(concurrent.getId());
                    rankRepository.flush();
                    savePrioritizationRanks(concurrent, prioritizationOptions);
                }
                Response saved = responseRepository.save(concurrent);
                publishResponseEvents(activity, true);
                return SubmitResponseResponse.builder()
                        .responseId(saved.getId())
                        .activityId(activityId)
                        .selectedOptionId(selectedOption != null ? selectedOption.getId() : null)
                        .textResponse(textResponse)
                        .orderedOptionIds(request.getOrderedOptionIds())
                        .revised(true)
                        .submittedAt(now)
                        .build();
            } else {
                throw new ConflictException("Duplicate response submission");
            }
        }
    }

    /**
     * Saves individual rank entries for a Prioritization response.
     *
     * @param response       parent Response entity
     * @param orderedOptions options in participant preference order
     */
    private void savePrioritizationRanks(Response response, List<ActivityOption> orderedOptions) {
        if (orderedOptions == null) return;
        int rank = 1;
        for (ActivityOption opt : orderedOptions) {
            PrioritizationRank pr = PrioritizationRank.builder()
                    .id(UUID.randomUUID())
                    .response(response)
                    .option(opt)
                    .rankOrder(rank++)
                    .build();
            rankRepository.save(pr);
        }
    }

    /**
     * Broadcasts WebSocket events to session and activity topics alerting clients of submissions.
     *
     * @param activity  activity answered
     * @param isRevised true if this updated a previous response
     */
    private void publishResponseEvents(Activity activity, boolean isRevised) {
        UUID sessionId = activity.getSession().getId();
        UUID activityId = activity.getId();

        Map<String, Object> payload = Map.of(
                "activityId", activityId.toString(),
                "activityType", activity.getType().name(),
                "revised", isRevised
        );

        // General submission notification
        eventPublisher.publishActivityEvent(WebSocketEventType.RESPONSE_SUBMITTED, sessionId, activityId, payload);
        eventPublisher.publishTeacherEvent(WebSocketEventType.RESPONSE_SUBMITTED, sessionId, activityId, payload);

        // Activity-type specific update notifications
        String updateType = switch (activity.getType()) {
            case WORD_CLOUD -> WebSocketEventType.WORD_CLOUD_UPDATED;
            case BRAINSTORMING -> WebSocketEventType.BRAINSTORMING_IDEA_ADDED;
            case PRIORITIZATION -> WebSocketEventType.PRIORITIZATION_UPDATED;
            default -> WebSocketEventType.RESULTS_UPDATED;
        };

        eventPublisher.publishActivityEvent(updateType, sessionId, activityId, payload);
        eventPublisher.publishTeacherEvent(updateType, sessionId, activityId, payload);
    }

    /**
     * Executes a server-side unbiased random student selection for Random Student Picker activities.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return selected participant details
     * @throws BadRequestException if activity is inactive, wrong type, or no participants in session
     * @throws ConflictException   if all eligible participants have already been picked
     */
    @Transactional
    public RandomPickerResponse pickRandomStudent(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        // Step 1: Validate session and teacher permissions
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Validate that target activity is ACTIVE and of type RANDOM_PICKER
        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);
        if (activity.getStatus() != ActivityStatus.ACTIVE) {
            throw new BadRequestException("Cannot pick random student on an activity that is not active");
        }
        if (activity.getType() != ActivityType.RANDOM_PICKER) {
            throw new BadRequestException("Activity is not a Random Student Picker");
        }

        // Step 3: Query all non-revoked session participants
        List<Participant> eligibleParticipants = participantRepository.findBySessionIdOrderByJoinedAtAsc(sessionId)
                .stream()
                .filter(p -> !p.isRevoked())
                .toList();

        if (eligibleParticipants.isEmpty()) {
            throw new BadRequestException("No eligible participants in session to pick from");
        }

        // Step 4: Exclude students picked in prior rounds to enforce selection without replacement
        List<RandomPickerSelection> previousSelections = randomPickerRepository.findByActivityId(activityId);
        List<Participant> candidatePool = new ArrayList<>(eligibleParticipants);

        Set<UUID> previouslyPickedIds = previousSelections.stream()
                .map(s -> s.getParticipant().getId())
                .collect(Collectors.toSet());

        candidatePool = candidatePool.stream()
                .filter(p -> !previouslyPickedIds.contains(p.getId()))
                .toList();

        // If everyone has already been picked, notify teacher
        if (candidatePool.isEmpty()) {
            throw new ConflictException("All eligible participants have already been picked in this activity");
        }

        // Step 5: Perform cryptographically secure draw
        int selectedIndex = secureRandom.nextInt(candidatePool.size());
        Participant chosen = candidatePool.get(selectedIndex);
        Instant now = clock.instant();

        // Step 6: Record selection in database audit trail
        RandomPickerSelection selection = RandomPickerSelection.builder()
                .id(UUID.randomUUID())
                .activity(activity)
                .participant(chosen)
                .pickedAt(now)
                .build();
        RandomPickerSelection saved = randomPickerRepository.save(selection);

        // Step 7: Assemble response
        RandomPickerResponse response = RandomPickerResponse.builder()
                .selectionId(saved.getId())
                .activityId(activityId)
                .participantId(chosen.getId())
                .displayName(chosen.getDisplayName())
                .pickedAt(now)
                .totalEligible(candidatePool.size() - 1)
                .totalPickedSoFar(previousSelections.size() + 1)
                .build();

        // Step 8: Broadcast RANDOM_PICKER_SELECTED event (sanitizing tokens)
        Map<String, Object> eventPayload = Map.of(
                "activityId", activityId.toString(),
                "participantId", chosen.getId().toString(),
                "displayName", chosen.getDisplayName(),
                "pickedAt", now.toString()
        );
        eventPublisher.publishActivityEvent(WebSocketEventType.RANDOM_PICKER_SELECTED, sessionId, activityId, eventPayload);
        eventPublisher.publishTeacherEvent(WebSocketEventType.RANDOM_PICKER_SELECTED, sessionId, activityId, eventPayload);

        return response;
    }

    /**
     * Updates visibility flags (showCorrectAnswer, resultsVisible) for an activity.
     * Restricted to authenticated teachers.
     *
     * @param sessionId    session UUID
     * @param activityId   activity UUID
     * @param request      visibility toggles
     * @param teacherToken teacher token
     * @param teacherKey   master key
     * @return updated activity response
     */
    @Transactional
    public ActivityResponse updateVisibility(UUID sessionId, UUID activityId, ActivityVisibilityRequest request, String teacherToken, String teacherKey) {
        // Step 1: Verify teacher access
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        // Step 2: Fetch activity and parse existing config
        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);
        ActivityConfig config = resultAggregationService.parseConfig(activity.getConfigJson());

        // Step 3: Apply requested visibility changes
        if (request.getShowCorrectAnswer() != null) {
            config.setShowCorrectAnswer(request.getShowCorrectAnswer());
        }
        if (request.getResultsVisible() != null) {
            config.setResultsVisible(request.getResultsVisible());
        }

        // Step 4: Serialize config back to JSON
        try {
            activity.setConfigJson(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity config", e);
        }

        // Step 5: Save activity entity
        Activity saved = activityRepository.save(activity);

        // Step 6: Broadcast RESULT_VISIBILITY_CHANGED event
        Map<String, Object> payload = Map.of(
                "activityId", activityId.toString(),
                "resultsVisible", config.isResultsVisible(),
                "showCorrectAnswer", config.isShowCorrectAnswer()
        );
        eventPublisher.publishActivityEvent(WebSocketEventType.RESULT_VISIBILITY_CHANGED, sessionId, activityId, payload);
        eventPublisher.publishTeacherEvent(WebSocketEventType.RESULT_VISIBILITY_CHANGED, sessionId, activityId, payload);

        return activityService.mapToResponse(saved, true);
    }

    /**
     * Resolves aggregated results and charts for an activity based on the caller's role.
     *
     * @param sessionId        session UUID
     * @param activityId       activity UUID
     * @param teacherToken     teacher token
     * @param teacherKey       master key
     * @param participantToken participant token
     * @return aggregated results DTO
     * @throws UnauthorizedException if no credentials supplied
     */
    @Transactional(readOnly = true)
    public ActivityResultResponse getResults(UUID sessionId, UUID activityId, String teacherToken, String teacherKey, String participantToken) {
        // Step 1: Resolve session and enforce expiry
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);

        // Step 2: Resolve activity
        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);

        // Step 3: Determine whether caller is teacher or student participant
        boolean isTeacher = false;
        if (participantToken != null && !participantToken.isBlank()) {
            participantSecurityService.authenticateParticipantForSession(participantToken, sessionId);
            isTeacher = false;
        } else if ((teacherToken != null && !teacherToken.isBlank()) || (teacherKey != null && !teacherKey.isBlank())) {
            teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);
            isTeacher = true;
        } else {
            throw new UnauthorizedException("Authentication credentials required to access activity results");
        }

        // Step 4: Delegate calculation to ResultAggregationService
        return resultAggregationService.aggregateResults(activity, isTeacher);
    }
}
