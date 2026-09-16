const test = require('node:test');
const assert = require('node:assert/strict');
const {validatePreparedIdentity} = require('./ci-timing-experiment');

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
const timingScript = path.join(__dirname, 'ci-timing-experiment.js');
const {groups, commandsFor} = require('./ci-timing-experiment');

test('prepared bundle preserves identity on another runner and receipts require that exact source', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'blue-prepared-source-'));
  const producer = path.join(directory, 'producer');
  const consumer = path.join(directory, 'consumer');
  const bundle = path.join(directory, 'bundle');
  const git = (...args) => execFileSync('git', args, {cwd: producer, encoding: 'utf8'}).trim();
  try {
    fs.mkdirSync(producer);
    git('init', '-q');
    git('config', 'user.name', 'Test');
    git('config', 'user.email', 'test@example.invalid');
    fs.writeFileSync(path.join(producer, '.cz.toml'), 'version = "1.0.0-rc.1"\n');
    git('add', '.cz.toml'); git('commit', '-qm', 'initial');
    const event = git('rev-parse', 'HEAD');
    execFileSync('git', ['clone', '-q', producer, consumer]);
    const env = {...process.env, GITHUB_SHA: event, GITHUB_RUN_ID: '42', GITHUB_RUN_ATTEMPT: '1',
      GITHUB_REF: 'refs/heads/codex/ci/language-parallel-experiment', BLUE_CI_SCOPE: 'rc17',
      BLUE_CI_DISTRIBUTED: 'verified-source', BLUE_CI_SOURCE: bundle,
      GITHUB_OUTPUT: path.join(directory, 'output'), GITHUB_ENV: path.join(directory, 'env')};
    execFileSync(process.execPath, [sourceScript, 'prepare', bundle, 'fixture'], {cwd: producer, env});
    const manifest = JSON.parse(fs.readFileSync(path.join(bundle, 'identity.json'), 'utf8'));
    assert.notEqual(manifest.commit, event);
    assert.equal(manifest.eventCommit, event);
    assert.equal(manifest.parentCommit, event);
    assert.equal(git('tag', '--list'), '');
    const restoreEnv = {...env, BLUE_CI_COMMIT: manifest.commit, BLUE_CI_TREE: manifest.sourceTree,
      BLUE_CI_PREPARED: 'true', CI_TRANSITION_RECEIPTS: path.join(directory, 'receipts')};
    execFileSync(process.execPath, [sourceScript, 'restore'], {cwd: consumer, env: restoreEnv, stdio: 'pipe'});
    assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], {cwd: consumer, encoding: 'utf8'}).trim(), manifest.commit);
    assert.match(fs.readFileSync(env.GITHUB_ENV, 'utf8'), /SOURCE_DATE_EPOCH=\d+/);
    const group = 'detached-retarget';
    const receiptPath = path.join(restoreEnv.CI_TRANSITION_RECEIPTS, 'rc17', 'attempt-1', group);
    fs.mkdirSync(receiptPath, {recursive: true});
    const receipt = {...manifest, group, success: true, elapsedSeconds: 1,
      commands: commandsFor(group).map(args => ({args, exitCode: 0, elapsedSeconds: 1}))};
    const receiptFile = path.join(receiptPath, `${group}.json`);
    fs.writeFileSync(receiptFile, JSON.stringify(receipt));
    const args = [timingScript, 'receipt', group, groups[group][0]];
    assert.equal(spawnSync(process.execPath, args, {cwd: consumer, env: restoreEnv}).status, 0);
    for (const patch of [{commit: event}, {runAttempt: '0'}, {commands: []}, {success: false}]) {
      fs.writeFileSync(receiptFile, JSON.stringify({...receipt, ...patch}));
      assert.equal(spawnSync(process.execPath, args, {cwd: consumer, env: restoreEnv}).status, 1);
    }
    const invalid = spawnSync(process.execPath, [sourceScript, 'check'], {
      cwd: consumer, env: {...restoreEnv, BLUE_CI_TREE: 'wrong'}, encoding: 'utf8'});
    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /Mismatched prepared sourceTree/);
    const rcGuard = spawnSync(process.execPath, [sourceScript, 'prepare', bundle, 'rc'], {
      cwd: consumer, env: {...env, GITHUB_SHA: manifest.commit}, encoding: 'utf8'});
    assert.equal(rcGuard.status, 1);
    assert.match(rcGuard.stderr, /RC preparation requires next/);
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});

test('isolated production-topology verification has no publication credentials or commands', () => {
  const workflow = fs.readFileSync(path.join(__dirname, '../workflows/verify-production-topology.yml'), 'utf8');
  const action = fs.readFileSync(path.join(__dirname, '../actions/verify-core/action.yml'), 'utf8');
  assert.doesNotMatch(workflow + action, /secrets\.|git\s+(?:push|tag)\b|jreleaserFullRelease|\.\/gradlew\s+publish\b/);
  assert.match(workflow, /refs\/heads\/codex\/ci\/language-parallel-experiment/);
  assert.match(action, /ci-verification-gate\.js/);
});

test('production release can publish only after the shared receipt verification barrier', () => {
  for (const [file, branch] of [['release-rc.yml', 'next']]) {
    const source = fs.readFileSync(path.join(__dirname, '../workflows', file), 'utf8');
    assert.ok(source.indexOf('./.github/actions/verify-core') < source.indexOf('run: ./gradlew publish'));
    assert.match(source, new RegExp(`github.ref == 'refs/heads/${branch}'`));
    assert.match(source, /needs: prepare/);
  }
});
