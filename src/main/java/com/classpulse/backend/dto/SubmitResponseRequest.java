package com.classpulse.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitResponseRequest {

    // For MCQ and Poll
    private UUID selectedOptionId;

    // For Word Cloud, Open Question, and Brainstorming
    private String textResponse;

    // For Prioritization
    private List<UUID> orderedOptionIds;
}
