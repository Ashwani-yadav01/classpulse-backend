package com.classpulse.backend.result;

import com.classpulse.backend.activity.Activity;
import com.classpulse.backend.activity.ActivityOption;
import com.classpulse.backend.activity.ActivityOptionRepository;
import com.classpulse.backend.activity.ActivityStatus;
import com.classpulse.backend.activity.config.ActivityConfig;
import com.classpulse.backend.common.exception.ForbiddenException;
import com.classpulse.backend.participant.ParticipantRepository;
import com.classpulse.backend.response.PrioritizationRank;
import com.classpulse.backend.response.PrioritizationRankRepository;
import com.classpulse.backend.response.RandomPickerSelection;
import com.classpulse.backend.response.RandomPickerSelectionRepository;
import com.classpulse.backend.response.Response;
import com.classpulse.backend.response.ResponseRepository;
import com.classpulse.backend.result.dto.ActivityResultResponse;
import com.classpulse.backend.result.dto.ActivityResultResponse.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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

    private ActivityResultResponse aggregateMcq(Activity activity, ActivityConfig config, boolean isTeacher, boolean canParticipantView, boolean isClosed) {
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        long totalVotes = responses.size();
        Map<UUID, Long> countMap = responses.stream()
                .filter(r -> r.getSelectedOption() != null)
                .collect(Collectors.groupingBy(r -> r.getSelectedOption().getId(), Collectors.counting()));

        boolean canSeeCorrectAnswer = isTeacher || isClosed || config.isShowCorrectAnswer();

        List<OptionResultDto> optionResults = options.stream().map(option -> {
            long count = countMap.getOrDefault(option.getId(), 0L);
            double percentage = totalVotes > 0 ? Math.round(((double) count / totalVotes) * 1000.0) / 10.0 : 0.0;

            return OptionResultDto.builder()
                    .optionId(option.getId())
                    .optionText(option.getOptionText())
                    .count(count)
                    .percentage(percentage)
                    .isCorrect(canSeeCorrectAnswer ? option.isCorrect() : null)
                    .build();
        }).toList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(totalVotes)
                .totalVotes(totalVotes)
                .resultsVisible(isTeacher || canParticipantView)
                .options(optionResults)
                .build();
    }

    private ActivityResultResponse aggregatePoll(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<ActivityOption> options = optionRepository.findByActivityIdOrderByDisplayOrderAsc(activity.getId());
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        long totalVotes = responses.size();
        Map<UUID, Long> countMap = responses.stream()
                .filter(r -> r.getSelectedOption() != null)
                .collect(Collectors.groupingBy(r -> r.getSelectedOption().getId(), Collectors.counting()));

        List<OptionResultDto> optionResults = options.stream().map(option -> {
            long count = countMap.getOrDefault(option.getId(), 0L);
            double percentage = totalVotes > 0 ? Math.round(((double) count / totalVotes) * 1000.0) / 10.0 : 0.0;

            return OptionResultDto.builder()
                    .optionId(option.getId())
                    .optionText(option.getOptionText())
                    .count(count)
                    .percentage(percentage)
                    .build();
        }).toList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(totalVotes)
                .totalVotes(totalVotes)
                .resultsVisible(isTeacher || canParticipantView)
                .options(optionResults)
                .build();
    }

    private ActivityResultResponse aggregateWordCloud(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        // Map normalized text -> list of original texts submitted
        Map<String, List<String>> normalizedToOriginals = new LinkedHashMap<>();
        for (Response r : responses) {
            String norm = r.getNormalizedText();
            if (norm != null && !norm.isBlank()) {
                normalizedToOriginals.computeIfAbsent(norm, k -> new ArrayList<>()).add(r.getTextResponse());
            }
        }

        // For display: choose the most common original display form for that normalized word
        List<WordCloudItemDto> wordCloudItems = normalizedToOriginals.entrySet().stream()
                .map(entry -> {
                    String norm = entry.getKey();
                    List<String> originalForms = entry.getValue();
                    long count = originalForms.size();

                    String displayWord = originalForms.stream()
                            .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                            .entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(norm);

                    return WordCloudItemDto.builder()
                            .word(displayWord)
                            .count(count)
                            .build();
                })
                .sorted(Comparator.comparingLong(WordCloudItemDto::getCount).reversed())
                .toList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(responses.size())
                .totalVotes((long) responses.size())
                .resultsVisible(isTeacher || canParticipantView)
                .wordCloud(wordCloudItems)
                .build();
    }

    private ActivityResultResponse aggregateOpenQuestion(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        List<OpenQuestionAnswerDto> answers = responses.stream()
                .filter(r -> r.getTextResponse() != null)
                .sorted(Comparator.comparing(Response::getSubmittedAt))
                .map(r -> OpenQuestionAnswerDto.builder()
                        .responseId(r.getId())
                        .answerText(r.getTextResponse())
                        .submittedAt(r.getSubmittedAt())
                        .displayName(isTeacher ? r.getParticipant().getDisplayName() : null)
                        .build())
                .toList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(answers.size())
                .totalVotes((long) answers.size())
                .resultsVisible(isTeacher || canParticipantView)
                .textAnswers(answers)
                .build();
    }

    private ActivityResultResponse aggregateBrainstorming(Activity activity, boolean isTeacher, boolean canParticipantView) {
        List<Response> responses = responseRepository.findByActivityId(activity.getId());

        List<BrainstormingIdeaDto> ideas = responses.stream()
                .filter(r -> r.getTextResponse() != null)
                .sorted(Comparator.comparing(Response::getSubmittedAt))
                .map(r -> BrainstormingIdeaDto.builder()
                        .responseId(r.getId())
                        .ideaText(r.getTextResponse())
                        .submittedAt(r.getSubmittedAt())
                        .build())
                .toList();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(ideas.size())
                .totalVotes((long) ideas.size())
                .resultsVisible(isTeacher || canParticipantView)
                .ideas(ideas)
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

        // Sort by score descending, secondary sort by original display order
        Map<UUID, Integer> displayOrderMap = options.stream()
                .collect(Collectors.toMap(ActivityOption::getId, ActivityOption::getDisplayOrder));

        List<OptionResultDto> sortedResults = options.stream().map(opt -> {
            int score = scoreMap.getOrDefault(opt.getId(), 0);
            return OptionResultDto.builder()
                    .optionId(opt.getId())
                    .optionText(opt.getOptionText())
                    .score(score)
                    .count(score)
                    .build();
        }).sorted((o1, o2) -> {
            int cmp = Integer.compare(o2.getScore(), o1.getScore());
            if (cmp != 0) return cmp;
            return Integer.compare(displayOrderMap.get(o1.getOptionId()), displayOrderMap.get(o2.getOptionId()));
        }).toList();

        // Assign ranks (handle ties: identical scores receive same rank)
        List<OptionResultDto> rankedOptions = new ArrayList<>();
        int currentRank = 1;
        for (int i = 0; i < sortedResults.size(); i++) {
            OptionResultDto current = sortedResults.get(i);
            if (i > 0 && current.getScore().equals(sortedResults.get(i - 1).getScore())) {
                current.setRank(rankedOptions.get(i - 1).getRank());
            } else {
                current.setRank(currentRank);
            }
            rankedOptions.add(current);
            currentRank++;
        }

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(responses.size())
                .totalVotes((long) responses.size())
                .resultsVisible(isTeacher || canParticipantView)
                .options(rankedOptions)
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

        long eligibleCount = participantRepository.findBySessionIdOrderByJoinedAtAsc(activity.getSession().getId())
                .stream()
                .filter(p -> !p.isRevoked())
                .count();

        RandomPickerResultDto pickerResult = RandomPickerResultDto.builder()
                .latestSelection(latest)
                .history(historyDtos)
                .totalEligible((int) eligibleCount)
                .totalPickedSoFar(historyDtos.size())
                .build();

        return ActivityResultResponse.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .activityType(activity.getType())
                .totalResponses(historyDtos.size())
                .totalVotes((long) historyDtos.size())
                .resultsVisible(isTeacher || canParticipantView)
                .randomPicker(pickerResult)
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
