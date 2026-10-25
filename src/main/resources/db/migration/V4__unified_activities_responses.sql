-- ClassPulse V4: Unified activities, text responses, prioritization ranks, and random picker history

-- 1. Make selected_option_id nullable in responses to support text-based activities
ALTER TABLE responses ALTER COLUMN selected_option_id DROP NOT NULL;

-- 2. Add text_response and normalized_text to responses
ALTER TABLE responses ADD COLUMN text_response VARCHAR(1000);
ALTER TABLE responses ADD COLUMN normalized_text VARCHAR(1000);

-- 3. Prioritization rankings table
CREATE TABLE prioritization_ranks (
    id UUID PRIMARY KEY,
    response_id UUID NOT NULL REFERENCES responses(id) ON DELETE CASCADE,
    option_id UUID NOT NULL REFERENCES activity_options(id) ON DELETE CASCADE,
    rank_order INT NOT NULL,
    CONSTRAINT uq_prioritization_response_option UNIQUE (response_id, option_id),
    CONSTRAINT uq_prioritization_response_rank UNIQUE (response_id, rank_order)
);

CREATE INDEX idx_prioritization_response ON prioritization_ranks(response_id);
CREATE INDEX idx_prioritization_option ON prioritization_ranks(option_id);

-- 4. Random picker selections history table
CREATE TABLE random_picker_selections (
    id UUID PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    picked_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_random_picker_activity ON random_picker_selections(activity_id);
CREATE INDEX idx_random_picker_participant ON random_picker_selections(participant_id);
