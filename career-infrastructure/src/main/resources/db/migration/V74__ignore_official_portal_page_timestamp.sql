UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'contentFingerprintIgnoreRegexes',
        jsonb_build_array('pageTimestamp\s*=\s*[''\"]\d+[''\"]')
    ),
    updated_at = now()
WHERE code IN ('ZJ_HRSS_INSTITUTION', 'HZ_CHUNAN_GOV');
