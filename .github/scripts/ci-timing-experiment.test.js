const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {execFileSync, spawnSync} = require('node:child_process');
const { groups, commandsFor, summarize, validateReceipt } = require('./ci-timing-experiment');

test('every delegated transition test has exactly one parallel owner', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../blue-conformance/build.gradle'), 'utf8');
  const registered = [...source.matchAll(/name: '([^']+ReleaseTransitionTest)'/g)]
    .map(match => `:blue-conformance:${match[1]}`).sort();
  const assigned = Object.values(groups).flat().sort();
  assert.equal(registered.length, 6);
  assert.deepEqual(assigned, registered);
  assert.equal(new Set(assigned).size, assigned.length);
  assert.ok(commandsFor('core').flat().every(value => value !== '-x'));
});

test('core preserves separate build and RC verification and imports remote results without exclusions', () => {
  assert.deepEqual(commandsFor('baseline').slice(0, 2), [['clean', 'build'], ['rcVerify']]);
  assert.deepEqual(commandsFor('core')[0], ['--init-script', '.github/scripts/ci-transition-receipts.init.gradle', 'clean', 'build']);
  assert.deepEqual(commandsFor('core')[1], ['--init-script', '.github/scripts/ci-transition-receipts.init.gradle', 'rcVerify']);
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
    group, commit: 'same-commit', sourceTree: 'same-tree', runId: '123', runAttempt: '1',
    elapsedSeconds: index === 0 ? 100 : 20, success: true,
    startedAtMs: 1000000, finishedAtMs: 1000000 + (index === 0 ? 100000 : 20000),
    commands: commandsFor(group).map(args => ({args, exitCode: 0, elapsedSeconds: 20})),
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

const identity = {commit: 'same-commit', sourceTree: 'same-tree', runId: '123', runAttempt: '1'};
function receipt(group = 'detached-retarget') {
  return {group, ...identity, success: true, elapsedSeconds: 20,
    commands: commandsFor(group).map(args => ({args, exitCode: 0, elapsedSeconds: 20}))};
}
test('only a complete successful same-source same-attempt receipt satisfies a transition', () => {
  const task = groups['detached-retarget'][0];
  assert.doesNotThrow(() => validateReceipt(receipt(), 'detached-retarget', task, identity));
  for (const field of ['commit', 'sourceTree', 'runId', 'runAttempt']) {
    assert.throws(() => validateReceipt({...receipt(), [field]: 'other'}, 'detached-retarget', task, identity));
  }
  for (const patch of [{success: false}, {commands: []}, {elapsedSeconds: 0},
    {commands: [{args: ['help'], exitCode: 0, elapsedSeconds: 20}]},
    {commands: [{args: groups['detached-retarget'], exitCode: 1, elapsedSeconds: 20}]}]) {
    assert.throws(() => validateReceipt({...receipt(), ...patch}, 'detached-retarget', task, identity));
  }
  assert.throws(() => validateReceipt(receipt(), 'detached-retarget', ':someOtherTest', identity));
});

test('comparison includes staggered command starts rather than assuming perfect overlap', () => {
  const values = reports();
  values[2].startedAtMs += 10000;
  values[2].finishedAtMs += 10000;
  assert.match(summarize(values), /30\.0 s/);
  assert.match(summarize(values), /70\.0%/);
});

const {compareProduction} = require('./ci-timing-experiment');
function productionReports() {
  return [...reports().filter(r => r.group !== 'core'),
    ...['rc25', 'build25', 'stable25'].map(group => ({group, ...identity, success: true,
      verificationScope: group === 'build25' ? 'build' : 'release',
      startedAtMs: 1000000, finishedAtMs: 1020000, elapsedSeconds: 20}))];
}
test('Java25 production comparison requires complete same-source RC and owners', () => {
  assert.match(compareProduction(productionReports()), /80\.0%/);
  assert.throws(() => compareProduction(productionReports().filter(r => r.group !== 'stable25')));
  assert.throws(() => compareProduction(productionReports().filter(r => r.group !== 'detached-retarget')));
  assert.throws(() => compareProduction(productionReports().map(r => r.group === 'baseline' ? {...r, commit: 'wrong'} : r)));
  assert.throws(() => compareProduction(productionReports().map(r => r.group === 'rc25' ? {...r, verificationScope: 'build'} : r)));
  assert.throws(() => compareProduction(productionReports().map(r => r.group === 'baseline' ? {...r, commands: []} : r)));
});
