-- ClassPulse V3: MCQ and Poll Responses

CREATE TABLE responses (
    id UUID PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    selected_option_id UUID NOT NULL REFERENCES activity_options(id) ON DELETE CASCADE,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_response_activity_participant UNIQUE (activity_id, participant_id)
);

CREATE INDEX idx_responses_activity ON responses(activity_id);
CREATE INDEX idx_responses_participant ON responses(participant_id);
