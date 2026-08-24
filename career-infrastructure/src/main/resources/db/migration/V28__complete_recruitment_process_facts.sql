ALTER TABLE recruitment_event
    ADD COLUMN notice_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN application_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN qualification_review_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN payment_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN admission_ticket_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN physical_exam_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN investigation_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN publication_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN appointment_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN physical_exam_rule TEXT,
    ADD COLUMN investigation_rule TEXT,
    ADD COLUMN publication_rule TEXT,
    ADD COLUMN appointment_rule TEXT;

UPDATE recruitment_event
SET notice_state = CASE WHEN published_on IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END,
    application_state = CASE
        WHEN application_starts_on IS NOT NULL OR application_starts_at IS NOT NULL THEN 'CONFIRMED'
        ELSE 'NOT_COLLECTED' END,
    qualification_review_state = CASE WHEN qualification_review_ends_on IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END,
    payment_state = CASE WHEN payment_ends_on IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END,
    admission_ticket_state = CASE
        WHEN admission_ticket_starts_on IS NOT NULL OR admission_ticket_ends_on IS NOT NULL THEN 'CONFIRMED'
        ELSE 'NOT_COLLECTED' END,
    physical_exam_rule = CASE WHEN employment_statement LIKE '%体检%' THEN employment_statement END,
    investigation_rule = CASE WHEN employment_statement LIKE '%考察%' THEN employment_statement END,
    publication_rule = CASE WHEN employment_statement LIKE '%公示%' THEN employment_statement END,
    appointment_rule = CASE
        WHEN employment_statement LIKE '%聘用%' OR employment_statement LIKE '%录用%'
          OR employment_statement LIKE '%签订%合同%' THEN employment_statement END;

UPDATE recruitment_event
SET physical_exam_state = CASE WHEN physical_exam_rule IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END,
    investigation_state = CASE WHEN investigation_rule IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END,
    publication_state = CASE WHEN publication_rule IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END,
    appointment_state = CASE WHEN appointment_rule IS NULL THEN 'NOT_COLLECTED' ELSE 'CONFIRMED' END;

ALTER TABLE recruitment_event
    ADD CONSTRAINT ck_recruitment_event_notice_state CHECK (notice_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_application_state CHECK (application_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_qualification_review_state CHECK (qualification_review_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_payment_state CHECK (payment_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_admission_ticket_state CHECK (admission_ticket_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_physical_exam_state CHECK (physical_exam_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_investigation_state CHECK (investigation_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_publication_state CHECK (publication_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT ck_recruitment_event_appointment_state CHECK (appointment_state IN
        ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN'));
