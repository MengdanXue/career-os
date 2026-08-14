INSERT INTO candidate_profile (
    id, display_name, birth_year, birth_month, highest_education, education_type,
    majors, experience_years, professional_titles, preferred_locations,
    accepted_employment_types, profile_version
) VALUES (
    '01992f09-0000-7000-8000-000000000001', 'MVP Seed Candidate', 1992, 12, 'MASTER', 'OVERSEAS',
    '["计算机科学与技术"]'::jsonb, NULL,
    '["中级：计算机应用（评审）"]'::jsonb,
    '["杭州", "浙江", "江苏", "广东", "福建"]'::jsonb,
    '["ESTABLISHMENT", "PERSONNEL_AGENCY", "CONTRACT"]'::jsonb,
    'master-spec-v1'
);
