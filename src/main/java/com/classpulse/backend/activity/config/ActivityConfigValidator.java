package com.classpulse.backend.activity.config;

import com.classpulse.backend.activity.ActivityType;
import com.classpulse.backend.activity.dto.ActivityOptionDto;
import com.classpulse.backend.activity.dto.CreateActivityRequest;
import com.classpulse.backend.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ActivityConfigValidator {

    public void validate(CreateActivityRequest request) {
        if (request.getType() == null) {
            throw new BadRequestException("Activity type is required");
        }
        if (request.getTitle() == null || request.getTitle().trim().isBlank()) {
            throw new BadRequestException("Activity title cannot be blank");
        }
        if (request.getTitle().trim().length() > 255) {
            throw new BadRequestException("Activity title cannot exceed 255 characters");
        }

        List<ActivityOptionDto> options = request.getOptions();

        switch (request.getType()) {
            case MCQ -> validateMcq(options);
            case POLL -> validatePoll(options);
            case PRIORITIZATION -> validatePrioritization(options);
            case WORD_CLOUD, OPEN_QUESTION, RANDOM_PICKER, BRAINSTORMING -> validateNoRequiredOptions(options, request.getType());
        }
    }

    private void validateMcq(List<ActivityOptionDto> options) {
        if (options == null || options.size() < 2) {
            throw new BadRequestException("Multiple Choice Question (MCQ) must have at least 2 options");
        }
        validateOptionTexts(options);
    }

    private void validatePoll(List<ActivityOptionDto> options) {
        if (options == null || options.size() < 2) {
            throw new BadRequestException("Poll must have at least 2 options");
        }
        validateOptionTexts(options);
    }

    private void validatePrioritization(List<ActivityOptionDto> options) {
        if (options == null || options.size() < 2) {
            throw new BadRequestException("Prioritization activity must have at least 2 items to rank");
        }
        validateOptionTexts(options);
    }

    private void validateOptionTexts(List<ActivityOptionDto> options) {
        for (int i = 0; i < options.size(); i++) {
            ActivityOptionDto opt = options.get(i);
            if (opt.getOptionText() == null || opt.getOptionText().trim().isBlank()) {
                throw new BadRequestException("Option " + (i + 1) + " text cannot be blank");
            }
            if (opt.getOptionText().trim().length() > 500) {
                throw new BadRequestException("Option " + (i + 1) + " text cannot exceed 500 characters");
            }
        }
    }

    private void validateNoRequiredOptions(List<ActivityOptionDto> options, ActivityType type) {
        // For Word Cloud, Open Question, Random Picker, Brainstorming, options are not required.
    }
}
