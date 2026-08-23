UPDATE candidate_profile
SET preferred_locations = '["杭州", "浙江"]'::jsonb,
    accepted_employment_types = '["ESTABLISHMENT", "PUBLIC_INSTITUTION_FORMAL"]'::jsonb,
    target_job_families = '["SOFTWARE", "DATA", "AI", "CYBERSECURITY", "INFORMATION_SYSTEMS", "DIGITALIZATION", "IT_OPERATIONS", "RESEARCH"]'::jsonb,
    preferred_organization_types = '["PUBLIC_INSTITUTION", "UNIVERSITY", "HOSPITAL", "RESEARCH_INSTITUTE", "STATE_OWNED_ENTERPRISE", "GOVERNMENT"]'::jsonb,
    updated_at = now()
WHERE id = '01992f09-0000-7000-8000-000000000001'
  AND profile_version = 'profile-v18-real-education'
  AND preferred_locations = '["杭州", "浙江", "江苏", "广东", "福建"]'::jsonb
  AND accepted_employment_types = '["ESTABLISHMENT", "PERSONNEL_AGENCY", "CONTRACT"]'::jsonb
  AND target_job_families = '[]'::jsonb
  AND preferred_organization_types = '[]'::jsonb;

INSERT INTO candidate_fact_confirmation (
    candidate_profile_id, fact_key, status, value_fingerprint, source, confirmed_at, updated_at
) SELECT seed.id, values.fact_key, values.status, values.value_fingerprint, values.source, now(), now()
FROM candidate_profile seed
CROSS JOIN (VALUES
('HIGHEST_EDUCATION', 'CONFIRMED',
 'a864252e923f03b5a1a56961d19a0d149d8f0a48bd7b16bc9da24c1e450045ba', 'USER_CONFIRMED', now(), now()),
('MAJORS', 'CONFIRMED',
 '243ca9a75ffb465a2ff0209e76100df55725b9ada6541b595edc93c0134a0655', 'USER_CONFIRMED', now(), now()),
('GRADUATION_YEAR', 'CONFIRMED',
 '96da37e95d5cc34fe3bef6c89428df859b8a217630d0c664da1daf1539caacf5', 'USER_CONFIRMED', now(), now()),
('PROFESSIONAL_TITLES', 'CONFIRMED',
 '6aeb4762cb425eb28a93876fbce1ae183a676330d5e48c502f03ecb9b3ed2722', 'USER_CONFIRMED', now(), now()),
('PREFERRED_LOCATIONS', 'CONFIRMED',
 '679c48f213b02aa588a4ff1149f8fbc86844f356ffcc22fec2d5db03a52c6b83', 'USER_CONFIRMED', now(), now()),
('ACCEPTED_EMPLOYMENT_TYPES', 'CONFIRMED',
 '9295ea3946d4ee557f64545ad6aed5eb0beb131a3a1b9c76b603daf885de53c2', 'USER_CONFIRMED', now(), now()),
('TARGET_JOB_FAMILIES', 'CONFIRMED',
 '39ccd6a13e51ec949c44df8b5f6e712f4c31cbcd0ddf0b0ddbcea6a645f56b57', 'USER_CONFIRMED', now(), now()),
('PREFERRED_ORGANIZATION_TYPES', 'CONFIRMED',
 '6522ff594982301b89a1317a7339c27fd498d9263879605314d48930b91a4941', 'USER_CONFIRMED', now(), now()),
('EDUCATION_RECORDS', 'CONFIRMED',
 '756d61df398ef78d14459e99b2b8d0b426aedc3ae959b87227593724a86ee87d', 'USER_CONFIRMED', now(), now())
) AS values(fact_key, status, value_fingerprint, source, ignored_confirmed_at, ignored_updated_at)
WHERE seed.id = '01992f09-0000-7000-8000-000000000001'
  AND seed.profile_version = 'profile-v18-real-education'
  AND seed.preferred_locations = '["杭州", "浙江"]'::jsonb
  AND seed.accepted_employment_types = '["ESTABLISHMENT", "PUBLIC_INSTITUTION_FORMAL"]'::jsonb
ON CONFLICT (candidate_profile_id, fact_key) DO NOTHING;

UPDATE candidate_fact_confirmation
SET status = 'UNKNOWN', confirmed_at = NULL, updated_at = now()
WHERE candidate_profile_id = '01992f09-0000-7000-8000-000000000001'
  AND fact_key IN ('EXPERIENCE_YEARS', 'POLITICAL_AFFILIATION', 'EMPLOYMENT_HISTORY')
  AND source = 'SEEDED';
