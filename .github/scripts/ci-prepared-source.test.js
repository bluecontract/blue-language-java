const test = require('node:test');
const assert = require('node:assert/strict');
const {validatePreparedIdentity} = require('./ci-transition-verification');

const manifest = {
  eventCommit: 'event-sha', commit: 'prepared-sha', sourceTree: 'prepared-tree',
  parentCommit: 'event-sha', runId: '123', runAttempt: '1', scope: 'rc', prepared: true,
};
const trusted = {...manifest};
const checkout = {commit: 'prepared-sha', sourceTree: 'prepared-tree', runId: '123', runAttempt: '1'};

test('prepared RC identity accepts HEAD different from event SHA with trusted preparation linkage', () => {
  assert.doesNotThrow(() => validatePreparedIdentity(manifest, trusted, checkout));
});

test('prepared identity rejects wrong source, event, parent, run, attempt and scope', () => {
  for (const key of ['eventCommit', 'commit', 'sourceTree', 'parentCommit', 'runId', 'runAttempt', 'scope']) {
    assert.throws(() => validatePreparedIdentity({...manifest, [key]: 'wrong'}, trusted, checkout));
  }
  for (const key of ['commit', 'sourceTree', 'runId', 'runAttempt']) {
    assert.throws(() => validatePreparedIdentity(manifest, trusted, {...checkout, [key]: 'wrong'}));
  }
  assert.throws(() => validatePreparedIdentity(manifest, {...trusted, eventCommit: 'other-event'}, checkout));
  assert.throws(() => validatePreparedIdentity(manifest, {...trusted, parentCommit: 'other-parent'}, checkout));
});

test('unprepared checkout must be the original event source', () => {
  const original = {...manifest, prepared: false, commit: 'event-sha', parentCommit: ''};
  assert.doesNotThrow(() => validatePreparedIdentity(original, original, {...checkout, commit: 'event-sha'}));
  const unrelated = {...original, commit: 'other'};
  assert.throws(() => validatePreparedIdentity(unrelated, unrelated, {...checkout, commit: 'other'}));
});

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {execFileSync, spawnSync} = require('node:child_process');
const sourceScript = path.join(__dirname, 'ci-verification-source.js');
const receiptScript = path.join(__dirname, 'ci-transition-verification.js');
const {groups, commandsFor} = require('./ci-transition-verification');

for (const mode of ['existing', 'rc']) {
  test(`${mode} source bundle survives a failed-job rerun with mixed owner attempts`, () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'blue-prepared-source-'));
    const producer = path.join(directory, 'producer');
    const consumer = path.join(directory, 'consumer');
    const bundle = path.join(directory, 'bundle');
    const git = (...args) => execFileSync('git', args, {cwd: producer, encoding: 'utf8'}).trim();
    try {
      fs.mkdirSync(path.join(producer, '.github/scripts'), {recursive: true});
      git('init', '-q'); git('config', 'user.name', 'Test'); git('config', 'user.email', 'test@example.invalid');
      fs.writeFileSync(path.join(producer, '.cz.toml'), 'version = "3.1.0-rc.1"\n');
      // Stub only version allocation/readiness; the real preparation/transfer and Git checks run.
      fs.writeFileSync(path.join(producer, '.github/scripts/prepare-rc-release.js'),
        `require('fs').writeFileSync('.cz.toml', 'version = "3.1.0-rc.2"\\n'.replace('\\\\n', '\\n'));`);
      fs.writeFileSync(path.join(producer, '.github/scripts/verify-release-readiness.js'), '');
      fs.writeFileSync(path.join(producer, 'gradlew'), '#!/bin/sh\nexit 7\n', {mode: 0o755});
      git('add', '.'); git('commit', '-qm', 'initial');
      const event = git('rev-parse', 'HEAD');
      execFileSync('git', ['clone', '-q', producer, consumer]);
      const env = {...process.env, GITHUB_SHA: event, GITHUB_RUN_ID: '42', GITHUB_RUN_ATTEMPT: '1',
        GITHUB_REF: 'refs/heads/next', GITHUB_REPOSITORY: 'blue/test', BLUE_CI_SCOPE: 'rc',
        BLUE_CI_DISTRIBUTED: 'verified-source', BLUE_CI_SOURCE: bundle,
        GITHUB_OUTPUT: path.join(directory, 'output'), GITHUB_ENV: path.join(directory, 'env')};
      execFileSync(process.execPath, [sourceScript, 'prepare', bundle, mode], {cwd: producer, env, stdio: 'pipe'});
      const prepared = JSON.parse(fs.readFileSync(path.join(bundle, 'identity.json'), 'utf8'));
      assert.equal(prepared.eventCommit, event);
      assert.equal(prepared.prepared, mode === 'rc');
      assert.equal(git('tag', '--list'), '');
      assert.match(fs.readFileSync(env.GITHUB_OUTPUT, 'utf8'), /attempt=1\n/);
      const restoreEnv = {...env, GITHUB_RUN_ATTEMPT: '2', BLUE_CI_SOURCE_ATTEMPT: '1',
        BLUE_CI_COMMIT: prepared.commit, BLUE_CI_TREE: prepared.sourceTree,
        BLUE_CI_PREPARED: String(prepared.prepared), CI_TRANSITION_RECEIPTS: path.join(directory, 'receipts')};
      execFileSync(process.execPath, [sourceScript, 'restore'], {cwd: consumer, env: restoreEnv, stdio: 'pipe'});
      assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], {cwd: consumer, encoding: 'utf8'}).trim(), prepared.commit);
      const bin = path.join(directory, 'bin'); fs.mkdirSync(bin);
      const jobFile = path.join(directory, 'jobs.json');
      const artifacts = path.join(directory, 'artifacts'); fs.mkdirSync(artifacts);
      fs.writeFileSync(path.join(bin, 'gh'), `#!${process.execPath}
const fs = require('node:fs'); const path = require('node:path');
const args = process.argv.slice(2);
if (args[0] === 'api') {
  if (!args.includes('--paginate') || !args.includes('--slurp') || !args.at(-1).includes('filter=all')) process.exit(3);
  process.stdout.write(fs.readFileSync(${JSON.stringify(jobFile)}));
} else {
  const name = args[args.indexOf('--name') + 1];
  const report = JSON.parse(fs.readFileSync(path.join(${JSON.stringify(artifacts)}, name + '.json')));
  fs.writeFileSync(path.join(args[args.indexOf('--dir') + 1], report.group + '.json'), JSON.stringify(report));
}
`, {mode: 0o755});
      restoreEnv.PATH = `${bin}:${process.env.PATH}`;
      // Defer validates source/assignment but must never contact the owners.
      // No jobs file exists yet, so an accidental gh lookup would fail.
      const deferArgs = [receiptScript, 'defer', 'detached-retarget', groups['detached-retarget'][0]];
      const deferEnv = {...restoreEnv, BLUE_CI_DEFER_TRANSITIONS: 'true'};
      assert.equal(spawnSync(process.execPath, deferArgs, {cwd: consumer, env: deferEnv}).status, 0);
      for (const patch of [{BLUE_CI_DEFER_TRANSITIONS: ''}, {BLUE_CI_TREE: 'wrong'}, {BLUE_CI_DISTRIBUTED: ''}]) {
        assert.equal(spawnSync(process.execPath, deferArgs, {cwd: consumer, env: {...deferEnv, ...patch}}).status, 1);
      }
      assert.equal(spawnSync(process.execPath, [receiptScript, 'defer', 'detached-retarget', ':wrong'],
        {cwd: consumer, env: deferEnv}).status, 1);

      for (const [index, group] of ['detached-retarget', 'reconciliation'].entries()) {
        const attempt = String(index + 1);
        fs.writeFileSync(jobFile, JSON.stringify([{jobs: [{name: `transitions / Python rc / ${group}`,
          run_attempt: Number(attempt), status: 'completed', conclusion: 'success'}]}]));
        const receiptPath = path.join(restoreEnv.CI_TRANSITION_RECEIPTS, 'rc', `attempt-${attempt}`, group);
        fs.mkdirSync(receiptPath, {recursive: true});
        const receipt = {...prepared, sourceAttempt: prepared.runAttempt, runAttempt: attempt, group, success: true, elapsedSeconds: 1,
          commands: commandsFor(group).map(args => ({args, exitCode: 0, elapsedSeconds: 1}))};
        const receiptFile = path.join(receiptPath, `${group}.json`);
        fs.writeFileSync(path.join(artifacts, `verification-rc-${attempt}-${group}.json`), JSON.stringify(receipt));
        const args = [receiptScript, 'receipt', group, groups[group][0]];
        assert.equal(spawnSync(process.execPath, args, {cwd: consumer, env: restoreEnv}).status, 0);
        for (const patch of [{commit: 'other'}, {runAttempt: '0'}, {commands: []}, {success: false}, {runId: 'other'}, {scope: 'stable'}, {sourceAttempt: '2'}]) {
          fs.writeFileSync(receiptFile, JSON.stringify({...receipt, ...patch}));
          assert.equal(spawnSync(process.execPath, args, {cwd: consumer, env: restoreEnv}).status, 1);
        }
      }
      // Exercise the exact workflow gate, including all six required receipts.
      for (const script of ['ci-verification-gate.js', 'ci-transition-verification.js', 'ci-verification-source.js']) {
        fs.copyFileSync(path.join(__dirname, script), path.join(consumer, '.github/scripts', script));
      }
      const jobs = [];
      for (const [index, group] of Object.keys(groups).entries()) {
        const attempt = String(index % 2 + 1);
        jobs.push({name: `transitions / Python rc / ${group}`, run_attempt: Number(attempt), status: 'completed', conclusion: 'success'});
        const receiptPath = path.join(restoreEnv.CI_TRANSITION_RECEIPTS, 'rc', `attempt-${attempt}`, group);
        fs.mkdirSync(receiptPath, {recursive: true});
        fs.writeFileSync(path.join(receiptPath, `${group}.json`), JSON.stringify({...prepared,
          sourceAttempt: prepared.runAttempt, runAttempt: attempt, group, success: true, elapsedSeconds: 1,
          commands: commandsFor(group).map(args => ({args, exitCode: 0, elapsedSeconds: 1}))}));
      }
      const gateArgs = ['.github/scripts/ci-verification-gate.js'];
      fs.writeFileSync(jobFile, JSON.stringify([{jobs}]));
      assert.equal(spawnSync(process.execPath, gateArgs, {cwd: consumer, env: restoreEnv}).status, 0);
      jobs[0] = {...jobs[0], run_attempt: 2, conclusion: 'failure'};
      fs.writeFileSync(jobFile, JSON.stringify([{jobs}]));
      const rejected = spawnSync(process.execPath, gateArgs, {cwd: consumer, env: restoreEnv, encoding: 'utf8'});
      assert.equal(rejected.status, 1);
      assert.match(rejected.stderr, /Transition owner failed/);
      for (const patch of [{BLUE_CI_TREE: 'wrong'}, {BLUE_CI_SOURCE_ATTEMPT: '2'}, {BLUE_CI_SOURCE_ATTEMPT: '0'}]) {
        assert.equal(spawnSync(process.execPath, [sourceScript, 'check'],
          {cwd: consumer, env: {...restoreEnv, ...patch}}).status, 1);
      }
      const failed = spawnSync(process.execPath, [receiptScript, 'run', 'detached-retarget', path.join(directory, 'failed')],
        {cwd: consumer, env: restoreEnv});
      assert.equal(failed.status, 1);
      const failure = JSON.parse(fs.readFileSync(path.join(directory, 'failed/detached-retarget.json'), 'utf8'));
      assert.equal(failure.success, false); assert.equal(failure.commands[0].exitCode, 7);
    } finally { fs.rmSync(directory, {recursive: true, force: true}); }
  });
}

test('release branch validation runs inside preparation without a standalone guard', () => {
  const prepare = fs.readFileSync(path.join(__dirname, '../workflows/prepare-verification-source.yml'), 'utf8');
  const command = prepare.match(/run: (case "\$RELEASE_SCOPE"[^\n]+)/)?.[1];
  assert.ok(command, 'first preparation step validates channel branch');
  assert.ok(prepare.indexOf(command) < prepare.indexOf('actions/checkout'));
  for (const [scope, branch] of [['rc', 'next'], ['stable', 'master']]) {
    for (const ref of [`refs/heads/${branch}`, 'refs/heads/untrusted', 'refs/tags/v3.1.0']) {
      assert.equal(spawnSync('bash', ['-c', command], {env: {...process.env, RELEASE_SCOPE: scope, RELEASE_REF: ref}}).status,
        ref === `refs/heads/${branch}` ? 0 : 1);
    }
  }
  assert.equal(spawnSync('bash', ['-c', command], {env: {...process.env, RELEASE_SCOPE: 'build', RELEASE_REF: 'refs/pull/1/merge'}}).status, 0);
  for (const file of ['release-rc.yml', 'release.yml']) {
    const source = fs.readFileSync(path.join(__dirname, '../workflows', file), 'utf8');
    assert.doesNotMatch(source, /  guard:|needs: guard/);
  }
});

test('all production consumers use the preparation attempt and bound the overall core job', () => {
  for (const file of ['build.yml', 'release-rc.yml', 'release.yml']) {
    const source = fs.readFileSync(path.join(__dirname, '../workflows', file), 'utf8');
    assert.match(source, /source-attempt: \$\{\{ needs.prepare.outputs.attempt \}\}/);
    assert.match(source, /BLUE_CI_SOURCE_ATTEMPT: \$\{\{ needs.prepare.outputs.attempt \}\}/);
    assert.match(source, /timeout-minutes: 90/);
    assert.doesNotMatch(source, /needs: \[prepare, transitions\]/);
  }
  const setup = fs.readFileSync(path.join(__dirname, '../actions/verification-setup/action.yml'), 'utf8');
  assert.match(setup, /name: source-\$\{\{ env.BLUE_CI_SCOPE \}\}-\$\{\{ env.BLUE_CI_SOURCE_ATTEMPT \}\}/);
});

test('successful preparation remains trusted when only failed consumers rerun', () => {
  assert.doesNotThrow(() => validatePreparedIdentity(manifest, trusted, {...checkout, runAttempt: '2'}));
  assert.throws(() => validatePreparedIdentity({...manifest, runAttempt: '2'}, trusted, {...checkout, runAttempt: '2'}));
});

test('receipt polling selects current owner execution without reusing superseded successes', () => {
  const {selectOwnerAttempt} = require('./ci-transition-verification');
  const job = (attempt, status = 'completed', conclusion = 'success') => ({
    name: 'transitions / Python rc / detached-retarget', run_attempt: attempt, status, conclusion,
  });
  assert.equal(selectOwnerAttempt([job(1)], 'rc', 'detached-retarget', '1', '2'), '1');
  assert.equal(selectOwnerAttempt([job(1), job(2, 'queued', null)], 'rc', 'detached-retarget', '1', '2'), null);
  assert.equal(selectOwnerAttempt([job(1), job(2, 'in_progress', null)], 'rc', 'detached-retarget', '1', '2'), null);
  assert.equal(selectOwnerAttempt([job(1, 'completed', 'failure')], 'rc', 'detached-retarget', '1', '2'), null);
  assert.throws(() => selectOwnerAttempt([job(1), job(2, 'completed', 'failure')], 'rc', 'detached-retarget', '1', '2'), /failed/);
  assert.equal(selectOwnerAttempt([job(1), job(2)], 'rc', 'detached-retarget', '1', '2'), '2');
  assert.equal(selectOwnerAttempt([job(1)], 'rc', 'detached-retarget', '2', '2'), null);
  assert.equal(selectOwnerAttempt([job(1)], 'stable', 'detached-retarget', '1', '2'), null);
});

test('core archive artifact names cannot collide across failed-job reruns', () => {
  for (const file of ['build.yml', 'release-rc.yml', 'release.yml']) {
    const source = fs.readFileSync(path.join(__dirname, '../workflows', file), 'utf8');
    const names = [...source.matchAll(/uses: actions\/upload-artifact@v4[\s\S]*?\n\s+name: ([^\n]+)/g)].map(match => match[1]);
    assert.ok(names.length > 0);
    for (const name of names) assert.match(name, /\$\{\{ github.run_attempt \}\}/);
  }
});

test('GitHub copied successful job records retain the original artifact execution attempt', () => {
  const {selectOwnerAttempt} = require('./ci-transition-verification');
  const original = {id: 105070797323, name: 'transitions / Python build / detached-retarget',
    run_attempt: 1, status: 'completed', conclusion: 'success',
    started_at: '2026-09-17T04:00:50Z', completed_at: '2026-09-17T04:10:21Z'};
  const copied = {...original, id: 105073686193, run_attempt: 2};
  assert.equal(selectOwnerAttempt([original, copied], 'build', 'detached-retarget', '1', '2'), '1');
  const rerun = {...copied, started_at: '2026-09-17T04:15:44Z', completed_at: '2026-09-17T04:25:00Z'};
  assert.equal(selectOwnerAttempt([original, rerun], 'build', 'detached-retarget', '1', '2'), '2');
  assert.equal(selectOwnerAttempt([original, {...copied, status: 'queued', completed_at: null}],
    'build', 'detached-retarget', '1', '2'), null);
});
