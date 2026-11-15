package com.classpulse.backend.service;

import com.classpulse.backend.dto.ActivityResultResponse;
import com.classpulse.backend.dto.ActivityResultResponse.*;
import com.classpulse.backend.dto.ResponseDetailDto;
import com.classpulse.backend.exception.ForbiddenException;
import com.classpulse.backend.model.*;
import com.classpulse.backend.repository.ActivityOptionRepository;
import com.classpulse.backend.repository.ParticipantRepository;
import com.classpulse.backend.repository.PrioritizationRankRepository;
import com.classpulse.backend.repository.RandomPickerSelectionRepository;
import com.classpulse.backend.repository.ResponseRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service calculating aggregate statistics, visual distributions, and audit breakdowns
 * across all pedagogical activity types (MCQ, Poll, Word Cloud, Open Question, Brainstorming,
 * Prioritization, Random Student Picker).
 * <p>
 * Key computational models:
 * <ul>
 *   <li>MCQ / Poll: Vote tallies, percentages rounded to 1 decimal place, correctness flags.</li>
 *   <li>Word Cloud: Case-insensitive token normalization, frequency counting, and original capitalization retention.</li>
 *   <li>Prioritization: Borda Count positional scoring algorithm:
 *       Given \(n\) options, rank 1 receives \(n\) points, rank 2 receives \(n-1\), down to rank \(n\) receiving 1 point.</li>
 *   <li>Role Masking: Student participants receive sanitized aggregate statistics; teachers receive detailed
 *       breakdowns including individual respondent names, submission timestamps, and milliseconds elapsed.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ResultAggregationService {

    /** Repository managing Response entities. */
    private final ResponseRepository responseRepository;

    /** Repository managing ActivityOption entities. */
    private final ActivityOptionRepository optionRepository;

    /** Repository managing PrioritizationRank records. */
    private final PrioritizationRankRepository rankRepository;

    /** Repository managing RandomPickerSelection audit history. */
    private final RandomPickerSelectionRepository randomPickerRepository;

    /** Repository managing Participant entities. */
    private final ParticipantRepository participantRepository;

    /** Jackson Object Mapper for deserializing activity JSON configuration. */
    private final ObjectMapper objectMapper;

    /**
     * Aggregates results for any activity based on its pedagogical type and the viewer's security role.
     *
     * @param activity  the Activity entity
     * @param isTeacher true if viewing as teacher; false if viewing as student participant
     * @return complete {@link ActivityResultResponse} DTO
     * @throws ForbiddenException if a student attempts to view results before they are made visible
     */
    @Transactional(readOnly = true)
    public ActivityResultResponse aggregateResults(Activity activity, boolean isTeacher) {
        // Step 1: Parse activity configuration JSON
        ActivityConfig config = parseConfig(activity.getConfigJson());

        // Step 2: Determine if results are visible to student participants
        boolean isClosed = activity.getStatus() == ActivityStatus.CLOSED;
        boolean canParticipantView = isClosed || config.isResultsVisible();

        // Step 3: Guard against unauthorized participant inspection
        if (!isTeacher && !canParticipantView) {
            throw new ForbiddenException("Results are not yet visible to participants");
        }

        // Step 4: Dispatch to specific activity aggregator
        return switch (activity.getType()) {
            case MCQ -> aggregateMcq(activity, config, isTeacher, canParticipantView, isClosed);
            case POLL -> aggregatePoll(activity, isTeacher, canParticipantView);
            case WORD_CLOUD -> aggregateWordCloud(activity, isTeacher, canParticipantView);
            case OPEN_QUESTION -> aggregateOpenQuestion(activity, isTeacher, canParticipantView);
            case BRAINSTORMING -> aggregateBrainstorming(activity, isTeacher, canParticipantView);
            case PRIORITIZATION -> aggregatePrioritization(activity, isTeacher, canParticipantView);
            case RANDOM_PICKER -> aggregateRandomPicker(activity, isTeacher, canParticipantView);
        };
    }

    /**
     * Computes the elapsed latency in milliseconds between activity activation and submission.
     *
     * @param activatedAt instant when activity opened
     * @param submittedAt instant when response was submitted
     * @return non-negative millisecond difference, or null if timestamps are missing
     */
    private Long computeResponseTimeMs(Instant activatedAt, Instant submittedAt) {
        if (activatedAt == null || submittedAt == null) return null;
        long ms = Duration.between(activatedAt, submittedAt).toMillis();
        return Math.max(0, ms);
    }

    /**
     * Builds a detailed teacher audit entry for a participant submission.
     *
     * @param r           the Response entity
     * @param activatedAt activity start instant
     * @param summary     human-readable answer text summary
     * @param isCorrect   correctness indicator (for scored activities)
     * @return constructed {@link ResponseDetailDto}
     */
    private ResponseDetailDto toResponseDetailDto(Response r, Instant activatedAt, String summary, Boolean isCorrect) {
        return ResponseDetailDto.builder()
                .responseId(r.getId())
                .participantId(r.getParticipant().getId())
                .displayName(r.getParticipant().getDisplayName())
                .answerSummary(summary)
                .submittedAt(r.getSubmittedAt())
                .responseTimeMs(computeResponseTimeMs(activatedAt, r.getSubmittedAt()))
                .isCorrect(isCorrect)
                .build();
    }

    /**
     * Computes MCQ option distribution, percentages, correctness reveals, and respondent breakdowns.
     */
    private ActivityResultResponse aggregateMcq(Activity activity, ActivityConfig config, boolean isTeacher, boolean canParticipantView, boolean isClosed) {
        // Step 1: Query configured options and submitted responses
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Step 2: Group responses by selected option ID
        long totalVotes = responses.size();
        Map<UUID, List<Response>> optionResponsesMap = responses.stream()
                .filter(r -> r.getSelectedOption() != null)
                .collect(Collectors.groupingBy(r -> r.getSelectedOption().getId()));

        // Step 3: Determine correct answer visibility policy
        boolean canSeeCorrectAnswer = isTeacher || isClosed || config.isShowCorrectAnswer();

        // Step 4: Compute count, percentage, and voter details for each option
        List<OptionResultDto> optionResults = options.stream().map(option -> {
            List<Response> optionResponses = optionResponsesMap.getOrDefault(option.getId(), Collections.emptyList());
            long count = optionResponses.size();
            double percentage = totalVotes > 0 ? Math.round(((double) count / totalVotes) * 1000.0) / 10.0 : 0.0;

            // Voter detail list is populated only for teachers
            List<ResponseDetailDto> voters = isTeacher ? optionResponses.stream()
                    .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), option.getOptionText(), option.isCorrect()))
                    .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                    .toList() : Collections.emptyList();

            return OptionResultDto.builder()
                    .optionId(option.getId())
                    .optionText(option.getOptionText())
                    .count(count)
                    .percentage(percentage)
                    .isCorrect(canSeeCorrectAnswer ? option.isCorrect() : null)
                    .voters(voters)
                    .build();
        }).toList();

        // Step 5: Assemble full detail log for teachers
        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> {
                    String optText = r.getSelectedOption() != null ? r.getSelectedOption().getOptionText() : null;
                    Boolean correct = r.getSelectedOption() != null ? r.getSelectedOption().isCorrect() : null;
                    return toResponseDetailDto(r, activity.getActivatedAt(), optText, correct);
                })
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        // Step 6: Construct composite response
        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(totalVotes)
                .totalVotes(totalVotes)
                .resultsVisible(isTeacher || canParticipantView)
                .options(optionResults)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Computes Poll voting results and percentages across choices.
     */
    private ActivityResultResponse aggregatePoll(Activity activity, boolean isTeacher, boolean canParticipantView) {
        // Step 1: Query options and responses
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Step 2: Group responses by selected option ID
        long totalVotes = responses.size();
        Map<UUID, List<Response>> optionResponsesMap = responses.stream()
                .filter(r -> r.getSelectedOption() != null)
                .collect(Collectors.groupingBy(r -> r.getSelectedOption().getId()));

        // Step 3: Compute vote count and percentage for each choice
        List<OptionResultDto> optionResults = options.stream().map(option -> {
            List<Response> optionResponses = optionResponsesMap.getOrDefault(option.getId(), Collections.emptyList());
            long count = optionResponses.size();
            double percentage = totalVotes > 0 ? Math.round(((double) count / totalVotes) * 1000.0) / 10.0 : 0.0;

            List<ResponseDetailDto> voters = isTeacher ? optionResponses.stream()
                    .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), option.getOptionText(), null))
                    .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                    .toList() : Collections.emptyList();

            return OptionResultDto.builder()
                    .optionId(option.getId())
                    .optionText(option.getOptionText())
                    .count(count)
                    .percentage(percentage)
                    .voters(voters)
                    .build();
        }).toList();

        // Step 4: Assemble teacher audit log
        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> {
                    String optText = r.getSelectedOption() != null ? r.getSelectedOption().getOptionText() : null;
                    return toResponseDetailDto(r, activity.getActivatedAt(), optText, null);
                })
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(totalVotes)
                .totalVotes(totalVotes)
                .resultsVisible(isTeacher || canParticipantView)
                .options(optionResults)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Clusters words by lowercase normalized text, counts frequencies, and retains the most common original display form.
     */
    private ActivityResultResponse aggregateWordCloud(Activity activity, boolean isTeacher, boolean canParticipantView) {
        // Step 1: Query all text responses submitted to this word cloud
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Step 2: Group responses by normalized text (case-folded lowercase)
        Map<String, List<Response>> normalizedToResponses = new LinkedHashMap<>();
        for (Response r : responses) {
            String norm = r.getNormalizedText();
            if (norm != null && !norm.isBlank()) {
                normalizedToResponses.computeIfAbsent(norm, k -> new ArrayList<>()).add(r);
            }
        }

        // Step 3: Map each cluster to a WordCloudItemDto using the dominant original capitalization
        List<WordCloudItemDto> wordCloudItems = normalizedToResponses.entrySet().stream()
                .map(entry -> {
                    String norm = entry.getKey();
                    List<Response> wordResponses = entry.getValue();
                    long count = wordResponses.size();

                    // Find most frequent capitalization among submitted variants
                    String displayWord = wordResponses.stream()
                            .map(Response::getTextResponse)
                            .filter(Objects::nonNull)
                            .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                            .entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(norm);

                    // Teacher view: list of respondents who submitted this word
                    List<ResponseDetailDto> respondents = isTeacher ? wordResponses.stream()
                            .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), r.getTextResponse(), null))
                            .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                            .toList() : Collections.emptyList();

                    return WordCloudItemDto.builder()
                            .word(displayWord)
                            .count(count)
                            .respondents(respondents)
                            .build();
                })
                .sorted(Comparator.comparingLong(WordCloudItemDto::getCount).reversed())
                .toList();

        // Step 4: Teacher response log
        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), r.getTextResponse(), null))
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(responses.size())
                .totalVotes((long) responses.size())
                .resultsVisible(isTeacher || canParticipantView)
                .wordCloud(wordCloudItems)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Aggregates qualitative textual responses for Open Question activities.
     */
    private ActivityResultResponse aggregateOpenQuestion(Activity activity, boolean isTeacher, boolean canParticipantView) {
        // Step 1: Query responses
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Step 2: Map to OpenQuestionAnswerDto; sanitize participant identity for student viewers
        List<OpenQuestionAnswerDto> answers = responses.stream()
                .filter(r -> r.getTextResponse() != null)
                .sorted(Comparator.comparing(Response::getSubmittedAt))
                .map(r -> OpenQuestionAnswerDto.builder()
                        .responseId(r.getId())
                        .participantId(isTeacher ? r.getParticipant().getId() : null)
                        .answerText(r.getTextResponse())
                        .submittedAt(r.getSubmittedAt())
                        .responseTimeMs(isTeacher ? computeResponseTimeMs(activity.getActivatedAt(), r.getSubmittedAt()) : null)
                        .displayName(isTeacher ? r.getParticipant().getDisplayName() : null)
                        .build())
                .toList();

        // Step 3: Teacher audit log
        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), r.getTextResponse(), null))
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(answers.size())
                .totalVotes((long) answers.size())
                .resultsVisible(isTeacher || canParticipantView)
                .textAnswers(answers)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Aggregates submitted ideas for Brainstorming activities.
     */
    private ActivityResultResponse aggregateBrainstorming(Activity activity, boolean isTeacher, boolean canParticipantView) {
        // Step 1: Query responses
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Step 2: Map to BrainstormingIdeaDto; sanitize identity if student viewer
        List<BrainstormingIdeaDto> ideas = responses.stream()
                .filter(r -> r.getTextResponse() != null)
                .sorted(Comparator.comparing(Response::getSubmittedAt))
                .map(r -> BrainstormingIdeaDto.builder()
                        .responseId(r.getId())
                        .participantId(isTeacher ? r.getParticipant().getId() : null)
                        .ideaText(r.getTextResponse())
                        .submittedAt(r.getSubmittedAt())
                        .responseTimeMs(isTeacher ? computeResponseTimeMs(activity.getActivatedAt(), r.getSubmittedAt()) : null)
                        .displayName(isTeacher ? r.getParticipant().getDisplayName() : null)
                        .build())
                .toList();

        // Step 3: Teacher audit log
        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), r.getTextResponse(), null))
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(ideas.size())
                .totalVotes((long) ideas.size())
                .resultsVisible(isTeacher || canParticipantView)
                .ideas(ideas)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Aggregates ranked choice submissions using the Borda Count scoring algorithm.
     * <p>
     * Scoring formula:
     * With \(n\) total options, rank 1 receives \(n\) points, rank 2 receives \(n - 1\) points, ..., rank \(n\) receives 1 point.
     */
    private ActivityResultResponse aggregatePrioritization(Activity activity, boolean isTeacher, boolean canParticipantView) {
        // Step 1: Query options and responses
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Step 2: Initialize score accumulator map with 0 points for every option
        int n = options.size();
        Map<UUID, Integer> scoreMap = new HashMap<>();
        for (ActivityOption opt : options) {
            scoreMap.put(opt.getId(), 0);
        }

        // Step 3: Apply Borda Count weighting across all participant submissions
        for (Response response : responses) {
            List<PrioritizationRank> ranks = rankRepository.findByResponseIdOrderByRankOrderAsc(response.getId());
            for (PrioritizationRank rank : ranks) {
                // Rank 1 gets n points; rank n gets 1 point
                int points = n - rank.getRankOrder() + 1;
                scoreMap.computeIfPresent(rank.getOption().getId(), (k, v) -> v + points);
            }
        }

        // Step 4: Sort options descending by accumulated Borda score
        List<OptionResultDto> sortedResults = options.stream().map(opt -> {
            int score = scoreMap.getOrDefault(opt.getId(), 0);
            return OptionResultDto.builder()
                    .optionId(opt.getId())
                    .optionText(opt.getOptionText())
                    .score(score)
                    .count(score)
                    .build();
        }).sorted(Comparator.comparingInt(OptionResultDto::getScore).reversed()).toList();

        // Step 5: Assign final ordinal ranks 1..N based on sorted score position
        for (int i = 0; i < sortedResults.size(); i++) {
            sortedResults.get(i).setRank(i + 1);
        }

        // Step 6: Teacher audit log
        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> toResponseDetailDto(r, activity.getActivatedAt(), "Submitted Ranking", null))
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(responses.size())
                .totalVotes((long) responses.size())
                .resultsVisible(isTeacher || canParticipantView)
                .options(sortedResults)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Aggregates selection history, most recent pick, and remaining eligible student count for Random Student Picker.
     */
    private ActivityResultResponse aggregateRandomPicker(Activity activity, boolean isTeacher, boolean canParticipantView) {
        // Step 1: Fetch historical selections in reverse chronological order
        List<RandomPickerSelection> history = randomPickerRepository.findByActivityIdOrderByPickedAtDesc(activity.getId());

        // Step 2: Transform history to SelectedParticipantDto
        List<SelectedParticipantDto> historyDtos = history.stream().map(s -> SelectedParticipantDto.builder()
                .participantId(s.getParticipant().getId())
                .displayName(s.getParticipant().getDisplayName())
                .pickedAt(s.getPickedAt())
                .build()).toList();

        // Step 3: Identify most recently drawn participant
        SelectedParticipantDto latest = historyDtos.isEmpty() ? null : historyDtos.get(0);

        // Step 4: Compute remaining eligible pool count
        List<UUID> pickedParticipantIds = history.stream()
                .map(s -> s.getParticipant().getId())
                .distinct()
                .toList();

        long eligibleCount = participantRepository.findBySessionIdOrderByJoinedAtAsc(activity.getSession().getId())
                .stream()
                .filter(p -> !p.isRevoked() && !pickedParticipantIds.contains(p.getId()))
                .count();

        // Step 5: Construct RandomPickerResultDto
        RandomPickerResultDto pickerResult = RandomPickerResultDto.builder()
                .latestSelection(latest)
                .history(historyDtos)
                .totalEligible((int) eligibleCount)
                .totalPickedSoFar(historyDtos.size())
                .build();

        // Step 6: Teacher audit log
        List<ResponseDetailDto> allDetails = isTeacher ? history.stream()
                .map(s -> ResponseDetailDto.builder()
                        .responseId(s.getId())
                        .participantId(s.getParticipant().getId())
                        .displayName(s.getParticipant().getDisplayName())
                        .answerSummary("Selected by Random Picker")
                        .submittedAt(s.getPickedAt())
                        .responseTimeMs(computeResponseTimeMs(activity.getActivatedAt(), s.getPickedAt()))
                        .build())
                .sorted(Comparator.comparing(ResponseDetailDto::getSubmittedAt))
                .toList() : Collections.emptyList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(historyDtos.size())
                .totalVotes((long) historyDtos.size())
                .resultsVisible(isTeacher || canParticipantView)
                .randomPicker(pickerResult)
                .responseDetails(allDetails)
                .build();
    }

    /**
     * Safely deserializes raw activity configuration JSON string into an {@link ActivityConfig} object.
     * Falls back to default configuration if JSON is null, empty, or unparseable.
     *
     * @param configJson raw JSON string
     * @return parsed ActivityConfig
     */
    public ActivityConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new ActivityConfig();
        }
        try {
            return objectMapper.readValue(configJson, ActivityConfig.class);
        } catch (Exception e) {
            return new ActivityConfig();
        }
    }
}
