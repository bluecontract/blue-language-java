const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = file => fs.readFileSync(path.join(__dirname, '..', file), 'utf8');

test('build phase requires the sentinel audit before the expensive build', () => {
  const action = read('actions/verify-core/action.yml');
  const start = action.indexOf('- name: Verify empty-object sentinel audit');
  assert.ok(start > action.indexOf('- name: Check source identity before verification'));
  assert.ok(start < action.indexOf('- name: Execute clean Gradle build'));
  const step = action.slice(start).split(/\n    - name:/)[0];
  assert.match(step, /if: inputs\.phase == 'build'/);
  assert.match(step, /run: \.\/gradlew --max-workers=4 --no-parallel verifyEmptySentinelAudit/);
  assert.doesNotMatch(step, /continue-on-error|\|\|\s*true/);
});

for (const file of ['build.yml', 'release-rc.yml', 'release.yml']) {
  test(`${file} exposes mandatory transition gate after core and before side effects`, () => {
    const workflow = read(`workflows/${file}`);
    const core = workflow.indexOf('uses: ./.github/actions/verify-core');
    const gate = workflow.indexOf('- name: Wait for transition verification');
    assert.ok(core >= 0 && gate > core, 'missing visible gate after core');
    const gateStep = workflow.slice(gate).split(/\n      - /)[0];
    assert.match(gateStep, /node \.github\/scripts\/ci-verification-source\.js check/);
    assert.match(gateStep, /node \.github\/scripts\/ci-verification-gate\.js/);
    assert.doesNotMatch(gateStep, /continue-on-error:|if:/);
    if (file !== 'build.yml') {
      assert.ok(workflow.indexOf('phase: release') > gate);
      assert.ok(workflow.indexOf('phase: release') < workflow.indexOf('Execute Gradle publish'));
    }
    for (const sideEffect of ['Configure Git for verified RC reservation', 'Execute Gradle publish', 'Archive libs']) {
      if (workflow.includes(sideEffect)) assert.ok(workflow.indexOf(sideEffect) > gate);
    }
  });
}

test('core delegates transition checks without polling and keeps publication forbidden', () => {
  const init = read('scripts/ci-transition-receipts.init.gradle');
  assert.match(init, /BLUE_CI_DEFER_TRANSITIONS.*'true' \? 'defer' : 'receipt'/);
  assert.match(init, /task\.commandLine\('node', script\.absolutePath, receiptMode, group, taskPath\)/);
  assert.match(init, /Publication is forbidden during distributed verification/);
  assert.doesNotMatch(read('actions/verify-core/action.yml'), /ci-verification-gate\.js/);
});

test('core rejects unknown or missing phases before any verification', () => {
  const action = read('actions/verify-core/action.yml');
  const guard = action.match(/run: (case "\$VERIFICATION_PHASE"[^\n]+)/);
  assert.ok(guard, 'missing phase guard');
  assert.ok(action.indexOf('name: Validate verification phase') < action.indexOf('name: Check source identity'));
  const {spawnSync} = require('node:child_process');
  for (const phase of ['build', 'release', '', 'typo']) {
    assert.equal(spawnSync('bash', ['-c', guard[1]], {env: {...process.env, VERIFICATION_PHASE: phase}}).status,
      ['build', 'release'].includes(phase) ? 0 : 1);
  }
});
