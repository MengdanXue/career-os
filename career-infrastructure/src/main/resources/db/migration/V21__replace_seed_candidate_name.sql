UPDATE candidate_profile
SET display_name = '测试候选人',
    updated_at = now()
WHERE id = '01992f09-0000-7000-8000-000000000001'
  AND display_name = 'MVP Seed Candidate';
