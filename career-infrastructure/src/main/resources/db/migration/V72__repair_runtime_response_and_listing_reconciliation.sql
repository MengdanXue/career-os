UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'responseRejectRegexes', jsonb_build_array('访问过于频繁，请稍后再试')
    ),
    updated_at = now()
WHERE code IN ('HZ_HRSS_INSTITUTION', 'ZJ_HRSS_INSTITUTION');

UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object(
        'reconcileReportedTotalByListingItems', true,
        'listingItemSelector', 'li',
        'itemLinkSelector', 'a[href]'
    ),
    updated_at = now()
WHERE code = 'HZ_GONGSHU_GOV';
