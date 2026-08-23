UPDATE candidate_fact_confirmation
SET value_fingerprint = 'fa5bbcbd85cdb0ed57f60533c850108e08e6f358db505bdf13ec455ffa9f299c',
    status = 'CONFIRMED',
    source = 'USER_CONFIRMED',
    confirmed_at = COALESCE(confirmed_at, now()),
    updated_at = now()
WHERE candidate_profile_id = '01992f09-0000-7000-8000-000000000001'
  AND fact_key = 'PROFESSIONAL_TITLES'
  AND value_fingerprint = '6aeb4762cb425eb28a93876fbce1ae183a676330d5e48c502f03ecb9b3ed2722'
  AND EXISTS (
      SELECT 1 FROM candidate_profile candidate
      WHERE candidate.id = candidate_fact_confirmation.candidate_profile_id
        AND candidate.profile_version = 'profile-v18-real-education'
        AND candidate.professional_titles = '["中级：计算机应用（评审）"]'::jsonb
  );
