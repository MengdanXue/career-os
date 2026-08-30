ALTER TABLE recruitment_lifecycle_document
    DROP CONSTRAINT recruitment_lifecycle_document_stage_check;

ALTER TABLE recruitment_lifecycle_document
    ADD CONSTRAINT recruitment_lifecycle_document_stage_check CHECK (stage IN (
        'QUALIFICATION_REVIEW', 'WRITTEN_EXAM', 'SCORE_RESULT', 'INTERVIEW',
        'PHYSICAL_EXAM', 'INVESTIGATION', 'PUBLICATION', 'APPOINTMENT'
    ));
