const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {groups, commandsFor, validateReceipt} = require('./ci-transition-verification');

test('every delegated transition test has exactly one owner and no arbitrary tasks', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../blue-conformance/build.gradle'), 'utf8');
  const registered = [...source.matchAll(/name: '([^']+ReleaseTransitionTest)'/g)]
    .map(match => `:blue-conformance:${match[1]}`).sort();
  assert.equal(registered.length, 6);
  assert.deepEqual(Object.values(groups).flat().sort(), registered);
  for (const group of Object.keys(groups)) assert.deepEqual(commandsFor(group), [groups[group]]);
  assert.throws(() => commandsFor('publish'));
  assert.throws(() => commandsFor('baseline'));
});

test('distributed Gradle invocation keeps all exclusions and publication prohibited', () => {
  const source = fs.readFileSync(path.join(__dirname, 'ci-transition-receipts.init.gradle'), 'utf8');
  assert.match(source, /excludedTaskNames/);
  assert.match(source, /name.contains\('jreleaser'\)/);
  assert.match(source, /name == 'publish'/);
  assert.match(source, /!name.endsWith\('tostagingrepository'\)/);
  assert.match(source, /Remote receipts require a verified CI source/);
});

const identity = {commit: 'same-commit', sourceTree: 'same-tree', runId: '123', runAttempt: '1', scope: 'rc', sourceAttempt: '1'};
function receipt(group = 'detached-retarget') {
  return {group, ...identity, success: true, elapsedSeconds: 20,
    commands: commandsFor(group).map(args => ({args, exitCode: 0, elapsedSeconds: 20}))};
}
test('only a complete successful same-source same-attempt receipt satisfies a transition', () => {
  const task = groups['detached-retarget'][0];
  assert.doesNotThrow(() => validateReceipt(receipt(), 'detached-retarget', task, identity));
  for (const field of ['commit', 'sourceTree', 'runId', 'runAttempt', 'scope', 'sourceAttempt']) {
    assert.throws(() => validateReceipt({...receipt(), [field]: 'other'}, 'detached-retarget', task, identity));
  }
  for (const patch of [{success: false}, {commands: []}, {elapsedSeconds: 0},
    {commands: [{args: ['help'], exitCode: 0, elapsedSeconds: 20}]},
    {commands: [{args: groups['detached-retarget'], exitCode: 1, elapsedSeconds: 20}]}]) {
    assert.throws(() => validateReceipt({...receipt(), ...patch}, 'detached-retarget', task, identity));
  }
  assert.throws(() => validateReceipt(receipt(), 'detached-retarget', ':someOtherTest', identity));
});
