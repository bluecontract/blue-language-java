const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {execFileSync, spawnSync} = require('node:child_process');
const { groups, commandsFor, summarize } = require('./ci-timing-experiment');

test('every excluded transition test has exactly one parallel owner', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../blue-conformance/build.gradle'), 'utf8');
  const registered = [...source.matchAll(/name: '([^']+ReleaseTransitionTest)'/g)]
    .map(match => `:blue-conformance:${match[1]}`).sort();
  const assigned = Object.values(groups).flat().sort();
  assert.equal(registered.length, 6);
  assert.deepEqual(assigned, registered);
  assert.equal(new Set(assigned).size, assigned.length);
  const core = commandsFor('core')[0];
  assert.deepEqual(core.filter((value, index) => core[index - 1] === '-x').sort(), registered);
});

test('baseline retains separate build and RC verification; core uses one task graph', () => {
  assert.deepEqual(commandsFor('baseline').slice(0, 2), [['clean', 'build'], ['rcVerify']]);
  assert.deepEqual(commandsFor('core')[0].slice(0, 4), ['clean', 'build', 'rcVerify', 'verifyFinalApiBaseline']);
  for (const group of Object.keys(groups)) assert.deepEqual(commandsFor(group), [groups[group]]);
});

test('no experiment entry point invokes remote publication or accepts arbitrary Gradle tasks', () => {
  for (const group of ['baseline', 'core', ...Object.keys(groups)]) {
    for (const command of commandsFor(group)) {
      assert.ok(command.every(arg => !/publish|jreleaser|deploy/i.test(arg)));
    }
  }
  assert.throws(() => commandsFor('jreleaserFullRelease'), /Unknown experiment group/);
});

function reports() {
  return ['baseline', 'core', ...Object.keys(groups)].map((group, index) => ({
    group, commit: 'same-commit', elapsedSeconds: index === 0 ? 100 : 20,
    success: true,
  }));
}

test('summary compares baseline with longest parallel group, not summed runner time', () => {
  assert.match(summarize(reports()), /100\.0/);
  assert.match(summarize(reports()), /20\.0/);
  assert.match(summarize(reports()), /80\.0%/);
});

test('missing, duplicate, failed or different-commit evidence cannot report success', () => {
  assert.throws(() => summarize(reports().slice(1)), /Missing/);
  assert.throws(() => summarize([...reports(), reports()[0]]), /Duplicate/);
  assert.throws(() => summarize(reports().map((r, i) => i === 2 ? {...r, success: false} : r)), /Failed/);
  assert.throws(() => summarize(reports().map((r, i) => i === 2 ? {...r, commit: 'different'} : r)), /commit/);
  assert.throws(() => summarize(reports().map((r, i) => i === 2 ? {...r, elapsedSeconds: NaN} : r)), /duration/);
});

test('a failed Gradle process fails the runner and leaves unsuccessful timing evidence', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'blue-ci-timing-test-'));
  try {
    execFileSync('git', ['init', '-q', directory]);
    fs.writeFileSync(path.join(directory, 'gradlew'), '#!/bin/sh\nexit 7\n', {mode: 0o755});
    execFileSync('git', ['add', 'gradlew'], {cwd: directory});
    execFileSync('git', ['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
      'commit', '-qm', 'fixture'], {cwd: directory});
    const result = spawnSync(process.execPath, [path.join(__dirname, 'ci-timing-experiment.js'),
      'run', 'detached-retarget', path.join(directory, 'timings')], {cwd: directory, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(fs.readFileSync(path.join(directory, 'timings/detached-retarget.json'), 'utf8'));
    assert.equal(report.success, false);
    assert.equal(report.commands[0].exitCode, 7);
    assert.ok(report.elapsedSeconds > 0);
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
});
