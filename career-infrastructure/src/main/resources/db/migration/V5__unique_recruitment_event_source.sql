DO $$
BEGIN
    IF EXISTS (
        SELECT source_url
        FROM recruitment_event
        GROUP BY source_url
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot add unique recruitment_event source constraint: duplicate source_url values exist';
    END IF;
END $$;

ALTER TABLE recruitment_event
    ADD CONSTRAINT uk_recruitment_event_source_url UNIQUE (source_url);
