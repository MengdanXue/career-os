UPDATE recruitment_source
SET configuration = jsonb_set(
        configuration,
        '{titleExcludeRegex}',
        to_jsonb('招聘会|培训|讲座'::text),
        true
    ),
    updated_at = now()
WHERE code = 'HZ_XIHU_GOV';
