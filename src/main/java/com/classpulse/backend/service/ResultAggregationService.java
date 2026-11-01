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

@Service
@RequiredArgsConstructor
public class ResultAggregationService {

    private final ResponseRepository responseRepository;
    private final ActivityOptionRepository optionRepository;
    private final PrioritizationRankRepository rankRepository;
    private final RandomPickerSelectionRepository randomPickerRepository;
    private final ParticipantRepository participantRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ActivityResultResponse aggregateResults(Activity activity, boolean isTeacher) {
        ActivityConfig config = parseConfig(activity.getConfigJson());

        boolean isClosed = activity.getStatus() == ActivityStatus.CLOSED;
        boolean canParticipantView = isClosed || config.isResultsVisible();

        if (!isTeacher && !canParticipantView) {
            throw new ForbiddenException("Results are not yet visible to participants");
        }

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

    private Long computeResponseTimeMs(Instant activatedAt, Instant submittedAt) {
        if (activatedAt == null || submittedAt == null) return null;
        long ms = Duration.between(activatedAt, submittedAt).toMillis();
        return Math.max(0, ms);
    }

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

    private ActivityResultResponse aggregateMcq(Activity activity, ActivityConfig config, boolean isTeacher, boolean canParticipantView, boolean isClosed) {
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        long totalVotes = responses.size();
        Map<UUID, List<Response>> optionResponsesMap = responses.stream()
                .filter(r -> r.getSelectedOption() != null)
                .collect(Collectors.groupingBy(r -> r.getSelectedOption().getId()));

        boolean canSeeCorrectAnswer = isTeacher || isClosed || config.isShowCorrectAnswer();

        List<OptionResultDto> optionResults = options.stream().map(option -> {
            List<Response> optionResponses = optionResponsesMap.getOrDefault(option.getId(), Collections.emptyList());
            long count = optionResponses.size();
            double percentage = totalVotes > 0 ? Math.round(((double) count / totalVotes) * 1000.0) / 10.0 : 0.0;

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

        List<ResponseDetailDto> allDetails = isTeacher ? responses.stream()
                .map(r -> {
                    String optText = r.getSelectedOption() != null ? r.getSelectedOption().getOptionText() : null;
                    Boolean correct = r.getSelectedOption() != null ? r.getSelectedOption().isCorrect() : null;
                    return toResponseDetailDto(r, activity.getActivatedAt(), optText, correct);
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

    private ActivityResultResponse aggregatePoll(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        long totalVotes = responses.size();
        Map<UUID, List<Response>> optionResponsesMap = responses.stream()
                .filter(r -> r.getSelectedOption() != null)
                .collect(Collectors.groupingBy(r -> r.getSelectedOption().getId()));

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

    private ActivityResultResponse aggregateWordCloud(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Map normalized text -> list of Response objects
        Map<String, List<Response>> normalizedToResponses = new LinkedHashMap<>();
        for (Response r : responses) {
            String norm = r.getNormalizedText();
            if (norm != null && !norm.isBlank()) {
                normalizedToResponses.computeIfAbsent(norm, k -> new ArrayList<>()).add(r);
            }
        }

        // For display: choose the most common original display form for that normalized word
        List<WordCloudItemDto> wordCloudItems = normalizedToResponses.entrySet().stream()
                .map(entry -> {
                    String norm = entry.getKey();
                    List<Response> wordResponses = entry.getValue();
                    long count = wordResponses.size();

                    String displayWord = wordResponses.stream()
                            .map(Response::getTextResponse)
                            .filter(Objects::nonNull)
                            .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                            .entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(norm);

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

    private ActivityResultResponse aggregateOpenQuestion(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

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

    private ActivityResultResponse aggregateBrainstorming(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

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

    private ActivityResultResponse aggregatePrioritization(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        int n = options.size();
        Map<UUID, Integer> scoreMap = new HashMap<>();
        for (ActivityOption opt : options) {
            scoreMap.put(opt.getId(), 0);
        }

        // Borda Count scoring: rank 1 gets n points, rank 2 gets n - 1 points, ..., rank n gets 1 point
        for (Response response : responses) {
            List<PrioritizationRank> ranks = rankRepository.findByResponseIdOrderByRankOrderAsc(response.getId());
            for (PrioritizationRank rank : ranks) {
                int points = n - rank.getRankOrder() + 1;
                scoreMap.computeIfPresent(rank.getOption().getId(), (k, v) -> v + points);
            }
        }

        List<OptionResultDto> sortedResults = options.stream().map(opt -> {
            int score = scoreMap.getOrDefault(opt.getId(), 0);
            return OptionResultDto.builder()
                    .optionId(opt.getId())
                    .optionText(opt.getOptionText())
                    .score(score)
                    .count(score)
                    .build();
        }).sorted(Comparator.comparingInt(OptionResultDto::getScore).reversed()).toList();

        // Assign ranks 1..N
        for (int i = 0; i < sortedResults.size(); i++) {
            sortedResults.get(i).setRank(i + 1);
        }

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

    private ActivityResultResponse aggregateRandomPicker(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<RandomPickerSelection> history = randomPickerRepository.findByActivityIdOrderByPickedAtDesc(activity.getId());

        List<SelectedParticipantDto> historyDtos = history.stream().map(s -> SelectedParticipantDto.builder()
                .participantId(s.getParticipant().getId())
                .displayName(s.getParticipant().getDisplayName())
                .pickedAt(s.getPickedAt())
                .build()).toList();

        SelectedParticipantDto latest = historyDtos.isEmpty() ? null : historyDtos.get(0);

        List<UUID> pickedParticipantIds = history.stream()
                .map(s -> s.getParticipant().getId())
                .distinct()
                .toList();

        long eligibleCount = participantRepository.findBySessionIdOrderByJoinedAtAsc(activity.getSession().getId())
                .stream()
                .filter(p -> !p.isRevoked() && !pickedParticipantIds.contains(p.getId()))
                .count();

        RandomPickerResultDto pickerResult = RandomPickerResultDto.builder()
                .latestSelection(latest)
                .history(historyDtos)
                .totalEligible((int) eligibleCount)
                .totalPickedSoFar(historyDtos.size())
                .build();

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
