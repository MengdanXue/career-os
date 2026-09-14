/** Launches only the manifest's loopback backend; credentials are random and process-local. */
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { existsSync, openSync, closeSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadIsolatedFixture } from './fixture.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = loadIsolatedFixture(process.env.CAREER_OS_E2E_FIXTURE_MANIFEST);
assert(process.env.JAVA_HOME && process.env.E2E_BCRYPT_CLASSPATH, 'Provide JAVA_HOME and E2E_BCRYPT_CLASSPATH');
const java = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const jar = path.join(root, 'career-web', 'target', 'career-web-0.1.0-SNAPSHOT.jar');
assert(existsSync(jar), 'Build the actual frontend and backend package first');
await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once('error', reject);
  server.listen({ host: '127.0.0.1', port: Number(new URL(fixture.manifest.baseUrl).port), exclusive: true }, () => server.close(resolve));
});
const password = randomBytes(32).toString('hex');
const username = `fixture-${randomBytes(6).toString('hex')}`;
const hash = execFileSync(java, ['--class-path', process.env.E2E_BCRYPT_CLASSPATH,
  path.join(root, 'e2e', 'FixturePasswordHash.java')], { encoding: 'utf8', windowsHide: true,
  env: { ...process.env, CAREER_E2E_HASH_INPUT: password } }).trim();
assert.match(hash, /^\$2[aby]\$10\$/);
const logPath = path.join(path.dirname(fixture.manifestPath), 'backend.log');
const log = openSync(logPath, 'a');
const backend = spawn(java, ['-jar', jar], { cwd: root, windowsHide: true,
  env: { ...process.env, ...fixture.backendEnvironment(),
    CAREER_OS_SECURITY_USERS_0_USERNAME: username, CAREER_OS_SECURITY_USERS_0_PASSWORD_HASH: hash,
    CAREER_OS_SECURITY_USERS_0_ROLES_0: 'ADMIN' }, stdio: ['ignore', log, log] });
try {
  let ready = false;
  for (let i = 0; i < 90; i++) {
    assert(backend.exitCode === null, `Backend exited ${backend.exitCode}; inspect ${logPath}`);
    try {
      const response = await fetch(`${fixture.manifest.baseUrl}/actuator/health`, {
        headers: { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}` },
        signal: AbortSignal.timeout(1500) });
      if (response.ok && (await response.json()).status === 'UP') { ready = true; break; }
    } catch { /* bounded readiness check */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert(ready, `Backend not ready; inspect ${logPath}`);
  fixture.seed(); // One-shot, refuses existing fixture data. No reset path.
  console.log(`Isolated backend ready at ${fixture.manifest.baseUrl}; synthetic fixture seeded.`);
  const exitCode = await new Promise((resolve, reject) => {
    const browser = spawn(process.execPath, [path.join(root, 'e2e', 'closed-loop.mjs')], { cwd: root,
      windowsHide: true, env: { ...process.env, E2E_USER: username, E2E_PASS: password }, stdio: 'inherit' });
    browser.once('error', reject);
    browser.once('exit', code => resolve(code ?? 1));
  });
  process.exitCode = exitCode;
} finally {
  // Exact child process created above only; never stop another backend or Docker engine.
  if (backend.exitCode === null) backend.kill('SIGTERM');
  closeSync(log);
}
