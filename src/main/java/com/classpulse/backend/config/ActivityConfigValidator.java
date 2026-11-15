package com.classpulse.backend.config;

import com.classpulse.backend.dto.ActivityOptionDto;
import com.classpulse.backend.dto.CreateActivityRequest;
import com.classpulse.backend.exception.BadRequestException;
import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityType;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain validator responsible for enforcing structural and business integrity rules
 * on activity creation requests across various activity types (MCQ, POLL, PRIORITIZATION, etc.).
 * <p>
 * Ensures that activities have valid titles, types, and appropriately sized option lists
 * matching their pedagogical model before persist operations commence in the service layer.
 */
@Component
public class ActivityConfigValidator {

    /**
     * Performs comprehensive validation on the given {@link CreateActivityRequest}.
     * Checks title presence, length boundaries, activity type presence, and type-specific rules.
     *
     * @param request the activity creation payload to validate
     * @throws BadRequestException if any validation constraint is violated
     */
    public void validate(CreateActivityRequest request) {
        // Step 1: Ensure activity type is explicitly specified
        if (request.getType() == null) {
            throw new BadRequestException("Activity type is required");
        }

        // Step 2: Validate that title exists and is not empty or composed solely of whitespace
        if (request.getTitle() == null || request.getTitle().trim().isBlank()) {
            throw new BadRequestException("Activity title cannot be blank");
        }

        // Step 3: Guard against database column overflow (maximum 255 characters allowed)
        if (request.getTitle().trim().length() > 255) {
            throw new BadRequestException("Activity title cannot exceed 255 characters");
        }

        // Extract the list of options supplied with the request
        List<ActivityOptionDto> options = request.getOptions();

        // Step 4: Dispatch to specific validator logic depending on the activity type
        switch (request.getType()) {
            case MCQ -> validateMcq(options);
            case POLL -> validatePoll(options);
            case PRIORITIZATION -> validatePrioritization(options);
            case WORD_CLOUD, OPEN_QUESTION, RANDOM_PICKER, BRAINSTORMING -> validateNoRequiredOptions(options, request.getType());
        }
    }

    /**
     * Validates configuration for Multiple Choice Questions (MCQ).
     * Requires at least two options and non-empty option text.
     *
     * @param options list of option DTOs provided in the request
     * @throws BadRequestException if options are missing, less than 2, or contain invalid text
     */
    private void validateMcq(List<ActivityOptionDto> options) {
        // An MCQ must present choices: minimum requirement is 2 distinct choices
        if (options == null || options.size() < 2) {
            throw new BadRequestException("Multiple Choice Question (MCQ) must have at least 2 options");
        }
        // Verify every individual option's text meets length and non-blank constraints
        validateOptionTexts(options);
    }

    /**
     * Validates configuration for Poll activities.
     * Requires at least two options and non-empty option text.
     *
     * @param options list of option DTOs provided in the request
     * @throws BadRequestException if options are missing, less than 2, or contain invalid text
     */
    private void validatePoll(List<ActivityOptionDto> options) {
        // A poll requires at least 2 options for participants to vote on
        if (options == null || options.size() < 2) {
            throw new BadRequestException("Poll must have at least 2 options");
        }
        // Validate each option's text integrity
        validateOptionTexts(options);
    }

    /**
     * Validates configuration for Prioritization activities.
     * Requires at least two items to establish a relative ranking order.
     *
     * @param options list of option DTOs provided in the request
     * @throws BadRequestException if options are missing, less than 2, or contain invalid text
     */
    private void validatePrioritization(List<ActivityOptionDto> options) {
        // Ranking requires at least 2 items to compare
        if (options == null || options.size() < 2) {
            throw new BadRequestException("Prioritization activity must have at least 2 items to rank");
        }
        // Validate each option's text integrity
        validateOptionTexts(options);
    }

    /**
     * Inspects every option DTO in the collection to ensure text is present and within 500 characters.
     *
     * @param options the list of options to check
     * @throws BadRequestException if an option text is blank or exceeds 500 characters
     */
    private void validateOptionTexts(List<ActivityOptionDto> options) {
        // Iterate through all options sequentially with 0-based index for precise error reporting
        for (int i = 0; i < options.size(); i++) {
            ActivityOptionDto opt = options.get(i);

            // Ensure option text is not null, empty, or whitespace-only
            if (opt.getOptionText() == null || opt.getOptionText().trim().isBlank()) {
                throw new BadRequestException("Option " + (i + 1) + " text cannot be blank");
            }

            // Enforce maximum database column length limit (500 chars)
            if (opt.getOptionText().trim().length() > 500) {
                throw new BadRequestException("Option " + (i + 1) + " text cannot exceed 500 characters");
            }
        }
    }

    /**
     * Validates activities that do not require pre-configured options.
     * For freeform formats (Word Cloud, Open Question, Brainstorming, Random Picker),
     * options are optional or derived at runtime from participant rosters.
     *
     * @param options list of option DTOs (may be null or empty)
     * @param type    the specific activity type
     */
    private void validateNoRequiredOptions(List<ActivityOptionDto> options, ActivityType type) {
        // For Word Cloud, Open Question, Random Picker, Brainstorming, options are not required.
        // No-op validation step intentionally maintained for architectural extensibility.
    }
}
