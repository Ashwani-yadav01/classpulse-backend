package com.classpulse.backend.model;

/**
 * Enumeration representing the distinct pedagogical activity modules supported by ClassPulse.
 */
public enum ActivityType {
    /**
     * Multiple Choice Question with one or more pre-set options and optional marked correct answers.
     */
    MCQ,

    /**
     * Opinion or feedback poll with multiple choices, supporting percentage calculation and vote updates.
     */
    POLL,

    /**
     * Real-time word cloud where student word submissions are normalized and frequency-counted.
     */
    WORD_CLOUD,

    /**
     * Qualitative freeform textual question allowing students to submit detailed text or paragraphs.
     */
    OPEN_QUESTION,

    /**
     * Random student picker mechanism choosing eligible participants without replacement during cold calls.
     */
    RANDOM_PICKER,

    /**
     * Collaborative idea generation where students post ideas/thoughts in real time.
     */
    BRAINSTORMING,

    /**
     * Ranked choice / prioritization module where students order items 1..N and Borda-count scores are aggregated.
     */
    PRIORITIZATION
}
