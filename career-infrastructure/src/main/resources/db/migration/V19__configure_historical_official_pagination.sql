UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'historicalPaginationMode', 'JCMS_PARAM_JSON',
        'historicalPageSize', 100,
        'historicalMaxPages', 20
    ),
    updated_at = now()
WHERE code IN ('ZJ_HRSS_INSTITUTION', 'HZ_HRSS_INSTITUTION');
