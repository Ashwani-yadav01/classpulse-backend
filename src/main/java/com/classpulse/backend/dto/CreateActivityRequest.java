package com.classpulse.backend.dto;

import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateActivityRequest {

    @NotNull(message = "Activity type is required")
    private ActivityType type;

    @NotBlank(message = "Activity title cannot be blank")
    @Size(max = 255, message = "Activity title cannot exceed 255 characters")
    private String title;

    private String configJson;

    private Integer orderIndex;

    @Builder.Default
    private List<ActivityOptionDto> options = new ArrayList<>();
}
