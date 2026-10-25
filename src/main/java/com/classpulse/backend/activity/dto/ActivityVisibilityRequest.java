package com.classpulse.backend.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityVisibilityRequest {
    private Boolean showCorrectAnswer;
    private Boolean resultsVisible;
}
