UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object('incrementalListingMaxPages', 2),
    updated_at = now()
WHERE code IN (
    'HDU_RECRUITMENT',
    'ZJGSU_RECRUITMENT',
    'HZ_GONGSHU_GOV',
    'HZ_HRSS_INSTITUTION',
    'ZJ_HRSS_INSTITUTION',
    'HZ_XIHU_GOV'
);

UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'contentFingerprintIgnoreRegexes', jsonb_build_array('点击量:\s*\d+')
    ),
    updated_at = now()
WHERE code = 'HZ_FIRST_HOSPITAL';

UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'contentFingerprintIgnoreRegexes', jsonb_build_array('浏览\s*\d+\s*次')
    ),
    updated_at = now()
WHERE code = 'HZ_TCM_HOSPITAL';

UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'contentFingerprintIgnoreRegexes',
        jsonb_build_array('<span><i class="icon2"></i>\s*\d+\s*</span>')
    ),
    updated_at = now()
WHERE code = 'HZ_XIXI_HOSPITAL';

UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'responseRejectRegexes', jsonb_build_array('访问过于频繁，请稍后再试')
    ),
    updated_at = now()
WHERE code = 'HZ_TONGLU_GOV';
