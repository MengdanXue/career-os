-- Earlier versions confirmed every field submitted by the one-click profile form.
-- For empty skills/research there was no separate "I explicitly have none" choice,
-- so those confirmations represented missing evidence rather than a known empty fact.
UPDATE candidate_fact_confirmation fact
SET status = 'UNKNOWN',
    confirmed_at = NULL,
    updated_at = now()
WHERE fact.candidate_profile_id = '01992f09-0000-7000-8000-000000000001'
  AND fact.fact_key IN ('SKILLS', 'RESEARCH_KEYWORDS')
  AND fact.status = 'CONFIRMED'
  AND fact.source = 'USER_CONFIRMED'
  AND fact.value_fingerprint = 'ba768b331fd86cec803be04e56ab2b3d4c0e98ef4ee4fcd4e72ad7cce61a1d1f'
  AND EXISTS (
      SELECT 1
      FROM candidate_profile candidate
      WHERE candidate.id = fact.candidate_profile_id
        AND (
            (fact.fact_key = 'SKILLS' AND candidate.skills = '[]'::jsonb)
            OR
            (fact.fact_key = 'RESEARCH_KEYWORDS' AND candidate.research_keywords = '[]'::jsonb)
        )
  );
