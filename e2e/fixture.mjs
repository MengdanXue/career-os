/** Isolated browser-test data. Never writes an existing app database or the migrated default candidate. */
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPOSITORY = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MARKER = 'career-os-stage1-synthetic-e2e-v1';
const LABEL = 'com.careeros.e2e.fixture';
const RUN_LABEL = 'com.careeros.e2e.run';
const PG_IMAGE = 'postgres:16-alpine';
const PG_PORT = 55439;
const API_PORT = 18089;
const MIGRATED_DEFAULT_CANDIDATE = '01992f09-0000-7000-8000-000000000001';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const sqlString = value => `'${String(value).replaceAll("'", "''")}'`;
const jsonSql = value => `${sqlString(JSON.stringify(value))}::jsonb`;
const sha256 = value => createHash('sha256').update(value, 'utf8').digest('hex');
const ordered = values => `${values.length}:` + values.map(value => `${value.length}:${value}`).join('');
const canonical = values => ordered(values.map(value => String(value).trim().normalize('NFKC')).sort());

function docker(args, options = {}) {
  return execFileSync('docker', args, {
    encoding: 'utf8', windowsHide: true, timeout: 30_000, maxBuffer: 8 * 1024 * 1024,
    stdio: ['pipe', 'pipe', 'pipe'], ...options,
  }).trim();
}

function inspect(kind, name) {
  return JSON.parse(docker([kind, 'inspect', name]))[0];
}

function exists(kind, name) {
  try { inspect(kind, name); return true; }
  catch (error) {
    if (/No such (container|object|volume)|not found/i.test(String(error.stderr))) return false;
    throw error;
  }
}

async function requireFreePort(port) {
  await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen({ host: '127.0.0.1', port, exclusive: true }, () => server.close(resolve));
  });
}

function validateManifest(manifest, manifestPath) {
  assert.equal(manifest.marker, MARKER, 'Not an isolated Career OS fixture manifest');
  assert.equal(manifest.schemaVersion, 1);
  assert.match(manifest.runId, /^\d{14}-[0-9a-f]{8}$/);
  assert.equal(manifest.containerName, `career-os-e2e-${manifest.runId}`);
  assert.equal(manifest.volumeName, `${manifest.containerName}-pgdata`);
  assert.equal(manifest.networkName, `${manifest.containerName}-network`);
  assert.equal(manifest.database, `career_os_e2e_${manifest.runId.replaceAll('-', '_')}`);
  assert.equal(manifest.databaseUser, 'career_os_e2e');
  assert.equal(manifest.image, PG_IMAGE);
  assert.match(manifest.imageId, /^sha256:[0-9a-f]{64}$/);
  assert.equal(manifest.databaseHost, '127.0.0.1');
  assert.equal(manifest.databasePort, PG_PORT);
  assert.equal(manifest.baseUrl, `http://127.0.0.1:${API_PORT}`);
  assert.equal(manifest.asOf, '2026-09-14');
  assert.equal(manifest.originalJobFamily, 'INFORMATION_SYSTEMS');
  assert.equal(manifest.syntheticDisplayName, `E2E Synthetic Candidate ${manifest.runId}`);
  for (const key of ['candidateId', 'firstJobId', 'secondJobId', 'organizationId',
    'firstEventId', 'secondEventId', 'evidenceId']) assert.match(manifest[key], UUID, key);
  assert.equal(new Set(['candidateId', 'firstJobId', 'secondJobId', 'organizationId',
    'firstEventId', 'secondEventId', 'evidenceId'].map(key => manifest[key])).size, 7);
  assert.equal(path.resolve(manifestPath), path.join(REPOSITORY, '.run', 'e2e-isolated', manifest.runId, 'manifest.json'));
  for (const key of Object.keys(manifest)) assert(!/password|secret|token/i.test(key), `Secret field forbidden: ${key}`);
}

/** Each operation checks labels, the pinned image, loopback mapping, volume and database marker. */
export function loadIsolatedFixture(manifestPath) {
  assert(manifestPath, 'CAREER_OS_E2E_FIXTURE_MANIFEST is required; no default database fallback');
  manifestPath = path.resolve(manifestPath);
  const manifest = Object.freeze(JSON.parse(readFileSync(manifestPath, 'utf8')));
  validateManifest(manifest, manifestPath);
  const q = sqlString;
  const runDirectory = path.dirname(manifestPath);
  function record(operation) {
    appendFileSync(path.join(runDirectory, 'connection-ledger.jsonl'), JSON.stringify({
      at: new Date().toISOString(), operation, container: manifest.containerName,
      database: manifest.database, candidateId: manifest.candidateId,
    }) + '\n', { encoding: 'utf8' });
  }
  function guardContainer() {
    const container = inspect('container', manifest.containerName);
    assert.equal(container.Config.Labels?.[LABEL], MARKER);
    assert.equal(container.Config.Labels?.[RUN_LABEL], manifest.runId);
    assert.equal(container.Image, manifest.imageId);
    assert.equal(container.State.Running, true, 'Isolated Postgres is not running');
    assert.equal(container.HostConfig.RestartPolicy.Name, 'no');
    const binding = container.NetworkSettings.Ports['5432/tcp'];
    assert.deepEqual(binding, [{ HostIp: '127.0.0.1', HostPort: String(PG_PORT) }]);
    assert(container.Mounts.some(mount => mount.Type === 'volume' &&
      mount.Name === manifest.volumeName && mount.Destination === '/var/lib/postgresql/data'));
    const volume = inspect('volume', manifest.volumeName);
    assert.equal(volume.Labels?.[LABEL], MARKER);
    assert.equal(volume.Labels?.[RUN_LABEL], manifest.runId);
    assert.deepEqual(Object.keys(container.NetworkSettings.Networks), [manifest.networkName]);
    const network = inspect('network', manifest.networkName);
    assert.equal(network.Labels?.[LABEL], MARKER);
    assert.equal(network.Labels?.[RUN_LABEL], manifest.runId);
    assert.equal(network.Internal, false);
    assert.equal(network.Options?.['com.docker.network.bridge.enable_icc'], 'false');
  }
  function sql(statement) {
    guardContainer();
    return docker(['exec', '-i', manifest.containerName, 'psql', '-X', '-q', '-t', '-A',
      '-v', 'ON_ERROR_STOP=1', '-U', manifest.databaseUser, '-d', manifest.database], { input: statement });
  }
  function guardSql(requireSeed = true) {
    return `DO $guard$ BEGIN
      IF current_database() <> ${q(manifest.database)} OR current_user <> ${q(manifest.databaseUser)}
      THEN RAISE EXCEPTION 'Wrong fixture database/user'; END IF;
      IF NOT EXISTS (SELECT 1 FROM career_os_e2e_guard.fixture WHERE marker = ${q(MARKER)}
        AND run_id = ${q(manifest.runId)} AND candidate_id = ${q(manifest.candidateId)}::uuid
        AND first_job_id = ${q(manifest.firstJobId)}::uuid AND second_job_id = ${q(manifest.secondJobId)}::uuid)
      THEN RAISE EXCEPTION 'Fixture marker mismatch'; END IF;
      ${requireSeed ? `IF NOT EXISTS (SELECT 1 FROM public.candidate_profile WHERE id = ${q(manifest.candidateId)}::uuid
          AND display_name = ${q(manifest.syntheticDisplayName)})
        THEN RAISE EXCEPTION 'Synthetic candidate mismatch'; END IF;
        IF (SELECT count(*) FROM public.job_posting) <> 2 OR
          (SELECT count(*) FROM public.job_posting WHERE id IN (${q(manifest.firstJobId)}::uuid, ${q(manifest.secondJobId)}::uuid)
           AND source_url LIKE ${q(`https://career-os-e2e.invalid/${manifest.runId}/%`)}) <> 2
        THEN RAISE EXCEPTION 'Synthetic job boundary mismatch'; END IF;` : ''}
      END $guard$;`;
  }
  // Hash, never export, the migrated candidate's personal data and all of its action/results tables.
  // Writing or recomputing through the default candidate breaks preservation evidence.
  // Read-only access is not observable in a row hash; the browser test also checks request IDs.
  function originalCandidateHashSql() {
    const candidate = `${q(MIGRATED_DEFAULT_CANDIDATE)}::uuid`;
    const collection = (table, order) => `(SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY ${order}), '[]'::jsonb)
      FROM public.${table} p WHERE candidate_profile_id = ${candidate})`;
    return `encode(sha256(convert_to(jsonb_build_object(
      'profile', (SELECT to_jsonb(p) FROM public.candidate_profile p WHERE id = ${candidate}),
      'facts', ${collection('candidate_fact_confirmation', 'fact_key')},
      'education', ${collection('candidate_education_record', 'record_order')},
      'employment', ${collection('candidate_employment_record', 'record_order')},
      'sessions', ${collection('agent_session', 'id')},
      'watches', ${collection('candidate_job_watch', 'job_posting_id')},
      'ledger', ${collection('profile_confirmation_ledger', 'idempotency_key')},
      'decisions', ${collection('decision_assessment', 'id')},
      'eligibility', ${collection('eligibility_assessment', 'id')},
      'fit', ${collection('fit_assessment', 'id')},
      'stability', ${collection('stability_assessment', 'id')},
      'opportunity', ${collection('opportunity', 'id')}
    )::text, 'UTF8')), 'hex')`;
  }
  function snapshot() {
    const result = JSON.parse(sql(`BEGIN READ ONLY; ${guardSql()}
      SELECT jsonb_build_object(
        'database', current_database(), 'runId', ${q(manifest.runId)},
        'candidate', (SELECT to_jsonb(c) FROM public.candidate_profile c WHERE c.id = ${q(manifest.candidateId)}::uuid),
        'facts', COALESCE((SELECT jsonb_agg(to_jsonb(f) ORDER BY fact_key) FROM public.candidate_fact_confirmation f
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'watches', COALESCE((SELECT jsonb_agg(to_jsonb(w) ORDER BY job_posting_id) FROM public.candidate_job_watch w
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'ledger', COALESCE((SELECT jsonb_agg(to_jsonb(l) ORDER BY recorded_at, idempotency_key) FROM public.profile_confirmation_ledger l
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'sessions', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY updated_at, id) FROM public.agent_session s
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'decisions', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY assessed_at, job_posting_id, id) FROM public.decision_assessment d
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'eligibilityAssessments', COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY assessed_at, job_posting_id, id) FROM public.eligibility_assessment e
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'fitAssessments', COALESCE((SELECT jsonb_agg(to_jsonb(f) ORDER BY assessed_at, job_posting_id, id) FROM public.fit_assessment f
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'stabilityAssessments', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY assessed_at, job_posting_id, id) FROM public.stability_assessment s
          WHERE candidate_profile_id = ${q(manifest.candidateId)}::uuid), '[]'::jsonb),
        'originalCandidatePreservation', (SELECT jsonb_build_object('candidateId', candidate_id,
          'baselineSha256', baseline_sha256, 'currentSha256', ${originalCandidateHashSql()},
          'unchanged', baseline_sha256 = ${originalCandidateHashSql()}, 'capturedAt', captured_at)
          FROM career_os_e2e_guard.original_candidate_baseline WHERE candidate_id = ${q(MIGRATED_DEFAULT_CANDIDATE)}::uuid),
        'jobs', (SELECT jsonb_agg(to_jsonb(j) ORDER BY CASE WHEN j.id = ${q(manifest.firstJobId)}::uuid THEN 0 ELSE 1 END) FROM public.job_posting j),
        'admission', (SELECT jsonb_agg(to_jsonb(a) ORDER BY job_posting_id) FROM public.job_admission a),
        'faultActive', (SELECT fault_active FROM career_os_e2e_guard.fixture WHERE run_id = ${q(manifest.runId)}),
        'flywayVersion', (SELECT max(version::integer) FROM public.flyway_schema_history WHERE success)
      ); COMMIT;`));
    record('snapshot');
    assert.equal(result.originalCandidatePreservation?.unchanged, true,
      'Migrated default candidate changed or received stateful requests; fixture evidence is invalid');
    return result;
  }
  function seed() {
    sql(`BEGIN; SELECT pg_advisory_xact_lock(721468399); ${guardSql(false)}
      DO $seed$ BEGIN
        IF (SELECT max(version::integer) FROM public.flyway_schema_history WHERE success) < 88
          OR NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '88' AND success)
        THEN RAISE EXCEPTION 'Run actual backend Flyway through V88 before seed'; END IF;
        IF EXISTS (SELECT 1 FROM public.job_posting)
          OR EXISTS (SELECT 1 FROM public.candidate_profile WHERE id = ${q(manifest.candidateId)}::uuid)
          OR EXISTS (SELECT 1 FROM career_os_e2e_guard.fixture WHERE seeded)
        THEN RAISE EXCEPTION 'Seed is one-shot; create a NEW fixture, never reset existing data'; END IF;
        IF NOT EXISTS (SELECT 1 FROM public.candidate_profile WHERE id = ${q(MIGRATED_DEFAULT_CANDIDATE)}::uuid)
        THEN RAISE EXCEPTION 'Migrated default candidate missing; preservation baseline unavailable'; END IF;
      END $seed$;
      CREATE TABLE career_os_e2e_guard.original_candidate_baseline (candidate_id uuid PRIMARY KEY,
        baseline_sha256 text NOT NULL CHECK (baseline_sha256 ~ '^[0-9a-f]{64}$'), captured_at timestamptz NOT NULL);
      INSERT INTO career_os_e2e_guard.original_candidate_baseline (candidate_id, baseline_sha256, captured_at)
        SELECT ${q(MIGRATED_DEFAULT_CANDIDATE)}::uuid, ${originalCandidateHashSql()}, now();
      ${seedSql(manifest)}
      UPDATE career_os_e2e_guard.fixture SET seeded = true WHERE run_id = ${q(manifest.runId)};
      ${guardSql()} COMMIT;`);
    record('seed');
    return snapshot();
  }
  function setRecomputeFailure(active) {
    assert.equal(typeof active, 'boolean');
    const before = snapshot();
    assert.equal(before.faultActive, !active, 'Fault toggle must change state; refuses accidental repeats');
    sql(`BEGIN; SELECT pg_advisory_xact_lock(721468399); ${guardSql()}
      DO $fault$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM public.job_posting WHERE id = ${q(manifest.secondJobId)}::uuid
          AND job_family = ${q(active ? manifest.originalJobFamily : 'E2E_INVALID_FOR_RECOMPUTE')})
        THEN RAISE EXCEPTION 'Unexpected pre-injection job family'; END IF;
      END $fault$;
      UPDATE public.job_posting SET job_family = ${q(active ? 'E2E_INVALID_FOR_RECOMPUTE' : manifest.originalJobFamily)}
        WHERE id = ${q(manifest.secondJobId)}::uuid;
      UPDATE career_os_e2e_guard.fixture SET fault_active = ${active} WHERE run_id = ${q(manifest.runId)};
      COMMIT;`);
    record(active ? 'recompute-failure-on' : 'recompute-failure-off');
    const after = snapshot();
    assert.deepEqual(after.candidate, before.candidate, 'Fault injection must not edit candidate data');
    assert.deepEqual(after.admission, before.admission, 'Fault injection must preserve admission');
    return after;
  }
  function backendEnvironment() {
    sql(`${guardSql(false)} SELECT current_database();`);
    const password = docker(['exec', manifest.containerName, 'printenv', 'POSTGRES_PASSWORD']);
    assert.match(password, /^[0-9a-f]{64}$/);
    record('backend-environment');
    return {
      CAREER_OS_DB_URL: `jdbc:postgresql://127.0.0.1:${PG_PORT}/${manifest.database}`,
      CAREER_OS_DB_USER: manifest.databaseUser, CAREER_OS_DB_PASSWORD: password,
      SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: String(API_PORT),
      CAREER_OS_ARTIFACT_ROOT: path.join(runDirectory, 'artifacts'),
      CAREER_OS_ACQUISITION_SCHEDULING_ENABLED: 'false', CAREER_OS_ACQUISITION_LIVE_SMOKE_ENABLED: 'false',
      CAREER_OS_LLM_ENABLED: 'false', CAREER_OS_AGENT_LLM_ENABLED: 'false', CAREER_OS_AI_CHAT_MODEL: 'none',
      CAREER_OS_AGENT_DYNAMIC_TOOLS_ENABLED: 'false', CAREER_OS_AGENT_ACCEPTANCE_ENABLED: 'false',
      CAREER_OS_AGENT_PLANNER: 'disabled',
      OPENAI_API_KEY: '',
    };
  }
  return Object.freeze({ manifest, manifestPath, snapshot, seed, setRecomputeFailure, backendEnvironment });
}

function seedSql(m) {
  const q = sqlString;
  const stamp = `${m.asOf}T00:00:00Z`;
  const source = suffix => `https://career-os-e2e.invalid/${m.runId}/${suffix}`;
  const education = ['合成大学', '中国', 'BACHELOR', '计算机科学与技术', '2026', '6', 'COMPLETED', 'NOT_REQUIRED'];
  const facts = {
    BIRTH_DATE: '2000-1-15', HIGHEST_EDUCATION: 'BACHELOR', MAJORS: canonical(['计算机科学与技术']),
    GRADUATION_YEAR: '2026', EXPERIENCE_YEARS: '0', PROFESSIONAL_TITLES: canonical(['测试工程师']),
    PREFERRED_LOCATIONS: canonical(['杭州']), ACCEPTED_EMPLOYMENT_TYPES: canonical(['ESTABLISHMENT', 'PUBLIC_INSTITUTION_FORMAL']),
    SKILLS: canonical(['Java', '信息系统']), RESEARCH_KEYWORDS: canonical([]),
    TARGET_JOB_FAMILIES: canonical(['INFORMATION_SYSTEMS']), PREFERRED_ORGANIZATION_TYPES: canonical(['PUBLIC_INSTITUTION']),
    EDUCATION_RECORDS: canonical([ordered(education)]), EMPLOYMENT_HISTORY: canonical([]),
    GENDER: 'UNKNOWN', POLITICAL_AFFILIATION: 'UNKNOWN',
    EMPLOYER_SETTLEMENT_AT_APPLICATION: 'UNDECLARED', SOCIAL_INSURANCE_AT_APPLICATION: 'UNDECLARED',
  };
  const pending = new Set(['GENDER', 'POLITICAL_AFFILIATION', 'EMPLOYER_SETTLEMENT_AT_APPLICATION', 'SOCIAL_INSURANCE_AT_APPLICATION']);
  const confirmations = Object.entries(facts).map(([key, value]) =>
    `(${q(m.candidateId)}, ${q(key)}, ${q(pending.has(key) ? 'UNCONFIRMED' : 'CONFIRMED')}, ${q(sha256(value))},
      'SEEDED', ${pending.has(key) ? 'NULL' : q(stamp)}, ${q(stamp)})`).join(',\n');
  const rule = restricted => ({ recruitmentYear: 2026, explicitGraduationYears: restricted ? [2026] : [],
    cohorts: restricted ? ['CURRENT_YEAR'] : ['UNRESTRICTED'], includesOverseasGraduates: false,
    degreeTiming: 'NOT_REQUIRED', degreeDeadline: null, credentialTiming: 'NOT_REQUIRED', credentialDeadline: null,
    requiresNoEmployer: restricted, restrictsSocialInsurance: restricted,
    rawText: restricted ? '合成测试条款：2026届，报名时未落实工作单位且无社保缴纳记录。' : '合成测试条款：无应届身份限制。',
    evidenceState: restricted ? 'CONFIRMED' : 'NOT_REQUIRED' });
  return `
    INSERT INTO public.candidate_profile (id, display_name, birth_year, birth_month, birth_day,
      highest_education, education_type, majors, graduation_year, experience_years, professional_titles,
      preferred_locations, accepted_employment_types, profile_version, skills, research_keywords,
      target_job_families, preferred_organization_types, gender, political_affiliation,
      employer_settlement_at_application, social_insurance_at_application)
    VALUES (${q(m.candidateId)}, ${q(m.syntheticDisplayName)}, 2000, 1, 15, 'BACHELOR', '合成全日制本科',
      '["计算机科学与技术"]', 2026, 0, '["测试工程师"]', '["杭州"]', '["ESTABLISHMENT","PUBLIC_INSTITUTION_FORMAL"]',
      ${q(`e2e-${m.runId}-v1`)}, '["Java","信息系统"]', '[]', '["INFORMATION_SYSTEMS"]', '["PUBLIC_INSTITUTION"]',
      'UNKNOWN', 'UNKNOWN', 'UNDECLARED', 'UNDECLARED');
    INSERT INTO public.candidate_education_record (candidate_profile_id, record_order, institution_name,
      country_or_region, education_level, major_name, graduation_year, graduation_month,
      completion_status, credential_verification_status)
    VALUES (${q(m.candidateId)}, 0, '合成大学', '中国', 'BACHELOR', '计算机科学与技术', 2026, 6, 'COMPLETED', 'NOT_REQUIRED');
    INSERT INTO public.candidate_fact_confirmation (candidate_profile_id, fact_key, status, value_fingerprint, source, confirmed_at, updated_at)
    VALUES ${confirmations};
    INSERT INTO public.evidence (id, evidence_type, source_url, source_title, excerpt, content_hash, captured_at)
    VALUES (${q(m.evidenceId)}, 'MANUAL_NOTE', ${q(source('evidence'))}, '仅限隔离E2E的合成证据',
      '完全虚构的自动化测试数据，不是真实招聘公告，不可用于求职判断。', ${q(sha256(MARKER + m.runId))}, ${q(stamp)});
    INSERT INTO public.organization (id, name, organization_type, province, city, official_website)
    VALUES (${q(m.organizationId)}, '合成测试事业单位（非真实机构）', 'PUBLIC_INSTITUTION', '浙江', '杭州', ${q(source('organization'))});
    ${[false, true].map(restricted => {
      const eventId = restricted ? m.secondEventId : m.firstEventId;
      return `INSERT INTO public.recruitment_event (id, title, recruitment_year, event_type, published_on,
        application_starts_on, application_ends_on, source_url, default_employment_type, evidence_ids,
        age_reference_date, graduate_rule, graduate_rule_json, notice_state, application_state)
      VALUES (${q(eventId)}, ${q(restricted ? '合成第二岗招聘事件' : '合成第一岗招聘事件')}, 2026, 'PUBLIC_INSTITUTION',
        '2026-09-01', '2026-09-01', '2026-12-31', ${q(source(`event-${restricted ? 2 : 1}`))},
        ${q(restricted ? 'PUBLIC_INSTITUTION_FORMAL' : 'ESTABLISHMENT')}, ${jsonSql([m.evidenceId])},
        ${q(m.asOf)}, ${q(rule(restricted).rawText)}, ${jsonSql(rule(restricted))}, 'CONFIRMED', 'CONFIRMED');`;
    }).join('\n')}
    ${[false, true].map(second => `INSERT INTO public.job_posting (id, recruitment_event_id, organization_id, external_job_code,
      title, job_family, employment_type, location, headcount, minimum_education, exact_majors,
      accepted_graduation_years, maximum_age, age_reference_date, minimum_experience_years, required_professional_titles,
      duties, source_url, evidence_ids, stable_job_key, content_fingerprint, active,
      education_requirement_text, major_requirement_text, gender_requirement, other_requirements,
      actual_employer, worksite, employment_identity_evidence)
    VALUES (${q(second ? m.secondJobId : m.firstJobId)}, ${q(second ? m.secondEventId : m.firstEventId)}, ${q(m.organizationId)},
      ${q(second ? 'E2E-SECOND' : 'E2E-FIRST')}, ${q(second ? '合成第二岗：信息系统管理' : '合成第一岗：信息系统研发')},
      'INFORMATION_SYSTEMS', ${q(second ? 'PUBLIC_INSTITUTION_FORMAL' : 'ESTABLISHMENT')}, '杭州', 1, 'BACHELOR',
      '["计算机科学与技术"]', ${jsonSql(second ? [2026] : [])}, 40, ${q(m.asOf)}, 0,
      ${jsonSql(second ? ['测试工程师'] : [])}, '合成测试：Java信息系统开发与维护。', ${q(source(`job-${second ? 2 : 1}`))},
      ${jsonSql([m.evidenceId])}, ${q(`e2e-${m.runId}-${second ? 2 : 1}`)}, ${q(sha256(`${m.runId}-${second}`))}, true,
      '本科及以上', '计算机科学与技术', ${second ? q('限女性') : 'NULL'}, ${second ? q('须为中共党员，具备测试工程师职称') : 'NULL'},
      '合成测试事业单位（非真实机构）', '杭州', ${q(second ? '合成测试：事业单位正式聘用。' : '合成测试：事业编制。')});`).join('\n')}
    UPDATE public.job_admission SET data_quality_status = 'VERIFIED', target_scope_status = 'INCLUDED',
      reason_codes = '["TARGET_TECHNICAL_ROLE"]', evaluator_version = 'synthetic-fixture-v1',
      assessed_at = ${q(stamp)}, human_verified = true
      WHERE job_posting_id IN (${q(m.firstJobId)}::uuid, ${q(m.secondJobId)}::uuid);
  `;
}

export async function createIsolatedFixture() {
  await requireFreePort(PG_PORT);
  await requireFreePort(API_PORT);
  const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 14) + '-' + randomBytes(4).toString('hex');
  const containerName = `career-os-e2e-${runId}`;
  const manifest = { schemaVersion: 1, marker: MARKER, runId, containerName,
    volumeName: `${containerName}-pgdata`, networkName: `${containerName}-network`,
    database: `career_os_e2e_${runId.replaceAll('-', '_')}`, databaseUser: 'career_os_e2e',
    databaseHost: '127.0.0.1', databasePort: PG_PORT, image: PG_IMAGE,
    imageId: inspect('image', PG_IMAGE).Id, baseUrl: `http://127.0.0.1:${API_PORT}`, asOf: '2026-09-14',
    candidateId: randomUUID(), firstJobId: randomUUID(), secondJobId: randomUUID(),
    organizationId: randomUUID(), firstEventId: randomUUID(), secondEventId: randomUUID(), evidenceId: randomUUID(),
    syntheticDisplayName: `E2E Synthetic Candidate ${runId}`, originalJobFamily: 'INFORMATION_SYSTEMS',
    createdAt: new Date().toISOString() };
  for (const [kind, name] of [['container', containerName], ['volume', manifest.volumeName], ['network', manifest.networkName]])
    assert(!exists(kind, name), `Refusing to reuse existing ${kind}: ${name}`);
  const runDirectory = path.join(REPOSITORY, '.run', 'e2e-isolated', runId);
  mkdirSync(path.dirname(runDirectory), { recursive: true });
  mkdirSync(runDirectory, { recursive: false });
  const manifestPath = path.join(runDirectory, 'manifest.json');
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx', encoding: 'utf8' });
  const labels = ['--label', `${LABEL}=${MARKER}`, '--label', `${RUN_LABEL}=${runId}`];
  docker(['volume', 'create', ...labels, manifest.volumeName]);
  // Docker does not publish host ports on an internal network. A dedicated bridge,
  // disabled inter-container communication and loopback-only binding preserve local isolation.
  docker(['network', 'create', '--opt', 'com.docker.network.bridge.enable_icc=false', ...labels, manifest.networkName]);
  docker(['run', '--detach', '--name', containerName, '--restart', 'no', ...labels,
    '--network', manifest.networkName, '--publish', `127.0.0.1:${PG_PORT}:5432`,
    '--mount', `type=volume,source=${manifest.volumeName},target=/var/lib/postgresql/data`,
    '--env', `POSTGRES_DB=${manifest.database}`, '--env', `POSTGRES_USER=${manifest.databaseUser}`,
    '--env', 'POSTGRES_PASSWORD', manifest.imageId],
    { env: { ...process.env, POSTGRES_PASSWORD: randomBytes(32).toString('hex') } });
  let ready = false;
  for (let attempt = 0; attempt < 45; attempt++) {
    try {
      docker(['exec', containerName, 'pg_isready', '-U', manifest.databaseUser, '-d', manifest.database]);
      ready = true; break;
    } catch { await new Promise(resolve => setTimeout(resolve, 500)); }
  }
  assert(ready, `Postgres did not become ready; retained manifest for diagnosis: ${manifestPath}`);
  docker(['exec', '-i', containerName, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1',
    '-U', manifest.databaseUser, '-d', manifest.database], { input: `BEGIN;
      DO $guard$ BEGIN IF current_database() <> ${sqlString(manifest.database)} THEN
        RAISE EXCEPTION 'Wrong new fixture database'; END IF; END $guard$;
      CREATE SCHEMA career_os_e2e_guard;
      CREATE TABLE career_os_e2e_guard.fixture (marker text NOT NULL, run_id text PRIMARY KEY,
        candidate_id uuid NOT NULL, first_job_id uuid NOT NULL, second_job_id uuid NOT NULL,
        seeded boolean NOT NULL DEFAULT false, fault_active boolean NOT NULL DEFAULT false);
      INSERT INTO career_os_e2e_guard.fixture (marker, run_id, candidate_id, first_job_id, second_job_id)
      VALUES (${sqlString(MARKER)}, ${sqlString(runId)}, ${sqlString(manifest.candidateId)},
        ${sqlString(manifest.firstJobId)}, ${sqlString(manifest.secondJobId)}); COMMIT;` });
  const fixture = loadIsolatedFixture(manifestPath);
  fixture.backendEnvironment(); // Validate labels, marker and host mapping without logging secrets.
  return manifestPath;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, manifestPath] = process.argv.slice(2);
  if (command === 'create') {
    mkdirSync(path.join(REPOSITORY, '.run', 'e2e-isolated'), { recursive: true });
    console.log(await createIsolatedFixture());
  } else if (['seed', 'snapshot', 'fault-on', 'fault-off'].includes(command)) {
    const fixture = loadIsolatedFixture(manifestPath);
    const result = command === 'seed' ? fixture.seed() : command === 'snapshot' ? fixture.snapshot()
      : fixture.setRecomputeFailure(command === 'fault-on');
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.error('Usage: node e2e/fixture.mjs create | (seed|snapshot|fault-on|fault-off) <manifest>');
    process.exitCode = 2;
  }
}
