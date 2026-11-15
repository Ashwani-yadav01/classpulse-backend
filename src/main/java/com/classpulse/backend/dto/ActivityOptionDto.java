package com.classpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Data Transfer Object representing an individual selectable choice or item in an activity
 * (such as an MCQ option, poll choice, or item in a prioritization list).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityOptionDto {

    /**
     * Unique identifier of the option. May be null during initial creation requests.
     */
    private UUID id;

    /**
     * Display label or text content of the option (e.g. "Linear Search", "Binary Search").
     */
    private String optionText;

    /**
     * Indicates whether this option represents the correct answer (for MCQs).
     * Omitted from participant payloads until results are revealed.
     */
    private Boolean isCorrect;

    /**
     * Display ordering position (zero-indexed) defining the layout sequence in the UI.
     */
    private Integer displayOrder;
}
