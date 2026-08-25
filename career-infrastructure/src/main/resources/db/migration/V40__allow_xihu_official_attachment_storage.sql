UPDATE recruitment_source
SET configuration = jsonb_set(
        configuration,
        '{allowedHosts}',
        (
            SELECT jsonb_agg(host ORDER BY host)
            FROM (
                SELECT DISTINCT host
                FROM jsonb_array_elements_text(
                    CASE
                        WHEN jsonb_typeof(configuration -> 'allowedHosts') = 'array'
                            THEN configuration -> 'allowedHosts'
                        ELSE '[]'::jsonb
                    END
                    || '["zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"]'::jsonb
                ) AS configured_hosts(host)
            ) AS deduplicated_hosts
        ),
        true
    ),
    updated_at = now()
WHERE code = 'HZ_XIHU_GOV';
