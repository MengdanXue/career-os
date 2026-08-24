ALTER TABLE recruitment_event
    ADD COLUMN graduate_rule_json JSONB,
    ADD COLUMN written_exam_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN professional_test_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN interview_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN interview_on DATE,
    ADD COLUMN interview_method TEXT,
    ADD COLUMN score_formula TEXT;

ALTER TABLE recruitment_event
    ADD CONSTRAINT ck_recruitment_event_written_exam_state
        CHECK (written_exam_state IN ('CONFIRMED', 'NOT_PUBLISHED', 'NOT_REQUIRED', 'NOT_COLLECTED',
            'PARSE_FAILED', 'REVIEW_REQUIRED', 'UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_professional_test_state
        CHECK (professional_test_state IN ('CONFIRMED', 'NOT_PUBLISHED', 'NOT_REQUIRED', 'NOT_COLLECTED',
            'PARSE_FAILED', 'REVIEW_REQUIRED', 'UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_interview_state
        CHECK (interview_state IN ('CONFIRMED', 'NOT_PUBLISHED', 'NOT_REQUIRED', 'NOT_COLLECTED',
            'PARSE_FAILED', 'REVIEW_REQUIRED', 'UNKNOWN'));
