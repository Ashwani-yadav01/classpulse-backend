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

@Service
@RequiredArgsConstructor
public class ResponseService {

    private final ResponseRepository responseRepository;
    private final ActivityRepository activityRepository;
    private final ActivityOptionRepository optionRepository;
    private final PrioritizationRankRepository rankRepository;
    private final RandomPickerSelectionRepository randomPickerRepository;
    private final ParticipantRepository participantRepository;
    private final ActivityService activityService;
    private final SessionService sessionService;
    private final TeacherSecurityService teacherSecurityService;
    private final ParticipantSecurityService participantSecurityService;
    private final ResultAggregationService resultAggregationService;
    private final WebSocketEventPublisher eventPublisher;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public SubmitResponseResponse submitResponse(UUID sessionId, UUID activityId, SubmitResponseRequest request, String participantToken) {
        Participant participant = participantSecurityService.authenticateParticipantForSession(participantToken, sessionId);

        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new BadRequestException("Cannot submit response to an inactive or expired session");
        }

        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);
        if (activity.getStatus() != ActivityStatus.ACTIVE) {
            throw new BadRequestException("Cannot submit response to an activity that is not active");
        }

        if (activity.getType() == ActivityType.RANDOM_PICKER) {
            throw new BadRequestException("Random Student Picker does not accept participant response submissions");
        }

        ActivityConfig config = resultAggregationService.parseConfig(activity.getConfigJson());
        boolean allowRevision = (activity.getType() == ActivityType.POLL) ? config.isAllowVoteRevision() : config.isAllowRevision();

        // 1. Validate payload based on activity type
        ActivityOption selectedOption = null;
        String textResponse = null;
        String normalizedText = null;
        List<ActivityOption> prioritizationOptions = null;

        switch (activity.getType()) {
            case MCQ, POLL -> {
                if (request.getSelectedOptionId() == null) {
                    throw new BadRequestException("selectedOptionId is required for " + activity.getType());
                }
                selectedOption = optionRepository.findByIdAndActivityId(request.getSelectedOptionId(), activityId)
                        .orElseThrow(() -> new BadRequestException("Selected option does not belong to this activity"));
            }
            case WORD_CLOUD -> {
                if (request.getTextResponse() == null || request.getTextResponse().trim().isBlank()) {
                    throw new BadRequestException("Word or phrase cannot be blank");
                }
                textResponse = request.getTextResponse().trim();
                int maxLen = config.getMaxWordLength() != null ? config.getMaxWordLength() : 50;
                if (textResponse.length() > maxLen) {
                    throw new BadRequestException("Word or phrase exceeds maximum length of " + maxLen + " characters");
                }
                normalizedText = textResponse.toLowerCase();
            }
            case OPEN_QUESTION, BRAINSTORMING -> {
                if (request.getTextResponse() == null || request.getTextResponse().trim().isBlank()) {
                    throw new BadRequestException("Text response cannot be blank");
                }
                textResponse = request.getTextResponse().trim();
                int maxLen = config.getMaxTextLength() != null ? config.getMaxTextLength() : 1000;
                if (textResponse.length() > maxLen) {
                    throw new BadRequestException("Text response exceeds maximum length of " + maxLen + " characters");
                }
                normalizedText = textResponse.toLowerCase();
            }
            case PRIORITIZATION -> {
                if (request.getOrderedOptionIds() == null || request.getOrderedOptionIds().isEmpty()) {
                    throw new BadRequestException("orderedOptionIds list is required for Prioritization");
                }
                List<ActivityOption> existingOptions = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activityId);
                if (request.getOrderedOptionIds().size() != existingOptions.size()) {
                    throw new BadRequestException("Ordered option list must contain exactly " + existingOptions.size() + " options");
                }

                Set<UUID> existingOptionIds = existingOptions.stream().map(ActivityOption::getId).collect(Collectors.toSet());
                Set<UUID> submittedOptionIds = new HashSet<>(request.getOrderedOptionIds());
                if (submittedOptionIds.size() != request.getOrderedOptionIds().size()) {
                    throw new BadRequestException("Duplicate option IDs found in prioritization ranking");
                }
                if (!existingOptionIds.equals(submittedOptionIds)) {
                    throw new BadRequestException("Prioritization rankings must contain only valid options belonging to this activity");
                }

                Map<UUID, ActivityOption> optionMap = existingOptions.stream().collect(Collectors.toMap(ActivityOption::getId, opt -> opt));
                prioritizationOptions = request.getOrderedOptionIds().stream().map(optionMap::get).toList();
            }
            case RANDOM_PICKER -> throw new BadRequestException("Random picker does not accept responses");
        }

        // 2. Check for existing response and enforce revision policy
        boolean isMultiSubmissionActivity = (activity.getType() == ActivityType.WORD_CLOUD || activity.getType() == ActivityType.BRAINSTORMING);
        List<Response> existingResponses = responseRepository.findByActivityIdAndParticipantId(activityId, participant.getId());
        Instant now = clock.instant();

        if (!isMultiSubmissionActivity && !existingResponses.isEmpty()) {
            if (!allowRevision) {
                throw new ConflictException("Participant has already submitted a response and revision is not allowed");
            }

            Response existing = existingResponses.get(0);
            existing.setSelectedOption(selectedOption);
            existing.setTextResponse(textResponse);
            existing.setNormalizedText(normalizedText);
            existing.setUpdatedAt(now);

            if (activity.getType() == ActivityType.PRIORITIZATION) {
                rankRepository.deleteByResponseId(existing.getId());
                rankRepository.flush();
                savePrioritizationRanks(existing, prioritizationOptions);
            }

            Response saved = responseRepository.save(existing);
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

        // 3. Create new Response
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
            Response saved = responseRepository.saveAndFlush(newResponse);

            if (activity.getType() == ActivityType.PRIORITIZATION) {
                savePrioritizationRanks(saved, prioritizationOptions);
            }

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
            // Concurrent submission for same participant
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

    private void publishResponseEvents(Activity activity, boolean isRevised) {
        UUID sessionId = activity.getSession().getId();
        UUID activityId = activity.getId();

        Map<String, Object> payload = Map.of(
                "activityId", activityId.toString(),
                "activityType", activity.getType().name(),
                "revised", isRevised
        );

        eventPublisher.publishActivityEvent(WebSocketEventType.RESPONSE_SUBMITTED, sessionId, activityId, payload);
        eventPublisher.publishTeacherEvent(WebSocketEventType.RESPONSE_SUBMITTED, sessionId, activityId, payload);

        // Activity-specific update events
        String updateType = switch (activity.getType()) {
            case WORD_CLOUD -> WebSocketEventType.WORD_CLOUD_UPDATED;
            case BRAINSTORMING -> WebSocketEventType.BRAINSTORMING_IDEA_ADDED;
            case PRIORITIZATION -> WebSocketEventType.PRIORITIZATION_UPDATED;
            default -> WebSocketEventType.RESULTS_UPDATED;
        };

        eventPublisher.publishActivityEvent(updateType, sessionId, activityId, payload);
        eventPublisher.publishTeacherEvent(updateType, sessionId, activityId, payload);
    }

    @Transactional
    public RandomPickerResponse pickRandomStudent(UUID sessionId, UUID activityId, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);
        if (activity.getStatus() != ActivityStatus.ACTIVE) {
            throw new BadRequestException("Cannot pick random student on an activity that is not active");
        }
        if (activity.getType() != ActivityType.RANDOM_PICKER) {
            throw new BadRequestException("Activity is not a Random Student Picker");
        }

        List<Participant> eligibleParticipants = participantRepository.findBySessionIdOrderByJoinedAtAsc(sessionId)
                .stream()
                .filter(p -> !p.isRevoked())
                .toList();

        if (eligibleParticipants.isEmpty()) {
            throw new BadRequestException("No eligible participants in session to pick from");
        }

        List<RandomPickerSelection> previousSelections = randomPickerRepository.findByActivityId(activityId);
        List<Participant> candidatePool = new ArrayList<>(eligibleParticipants);

        // Always exclude previously picked students so no student is picked twice
        Set<UUID> previouslyPickedIds = previousSelections.stream()
                .map(s -> s.getParticipant().getId())
                .collect(Collectors.toSet());

        candidatePool = candidatePool.stream()
                .filter(p -> !previouslyPickedIds.contains(p.getId()))
                .toList();

        if (candidatePool.isEmpty()) {
            throw new ConflictException("All eligible participants have already been picked in this activity");
        }

        // Server-side cryptographically secure selection
        int selectedIndex = secureRandom.nextInt(candidatePool.size());
        Participant chosen = candidatePool.get(selectedIndex);
        Instant now = clock.instant();

        RandomPickerSelection selection = RandomPickerSelection.builder()
                .id(UUID.randomUUID())
                .activity(activity)
                .participant(chosen)
                .pickedAt(now)
                .build();

        RandomPickerSelection saved = randomPickerRepository.save(selection);

        RandomPickerResponse response = RandomPickerResponse.builder()
                .selectionId(saved.getId())
                .activityId(activityId)
                .participantId(chosen.getId())
                .displayName(chosen.getDisplayName())
                .pickedAt(now)
                .totalEligible(candidatePool.size() - 1)
                .totalPickedSoFar(previousSelections.size() + 1)
                .build();

        // Publish WebSocket event after commit (never exposing private tokens or hashes)
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

    @Transactional
    public ActivityResponse updateVisibility(UUID sessionId, UUID activityId, ActivityVisibilityRequest request, String teacherToken, String teacherKey) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        teacherSecurityService.verifyTeacherSessionAccess(session, teacherToken, teacherKey);

        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);
        ActivityConfig config = resultAggregationService.parseConfig(activity.getConfigJson());

        if (request.getShowCorrectAnswer() != null) {
            config.setShowCorrectAnswer(request.getShowCorrectAnswer());
        }
        if (request.getResultsVisible() != null) {
            config.setResultsVisible(request.getResultsVisible());
        }

        try {
            activity.setConfigJson(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity config", e);
        }

        Activity saved = activityRepository.save(activity);

        // Publish visibility change event
        Map<String, Object> payload = Map.of(
                "activityId", activityId.toString(),
                "resultsVisible", config.isResultsVisible(),
                "showCorrectAnswer", config.isShowCorrectAnswer()
        );
        eventPublisher.publishActivityEvent(WebSocketEventType.RESULT_VISIBILITY_CHANGED, sessionId, activityId, payload);
        eventPublisher.publishTeacherEvent(WebSocketEventType.RESULT_VISIBILITY_CHANGED, sessionId, activityId, payload);

        return activityService.mapToResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public ActivityResultResponse getResults(UUID sessionId, UUID activityId, String teacherToken, String teacherKey, String participantToken) {
        Session session = sessionService.getSessionOrThrow(sessionId);
        sessionService.checkAndEnforceExpiry(session);

        Activity activity = activityService.getActivityOrThrow(activityId, sessionId);

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

        return resultAggregationService.aggregateResults(activity, isTeacher);
    }
}
