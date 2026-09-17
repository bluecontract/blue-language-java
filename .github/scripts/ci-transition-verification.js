const fs = require('node:fs');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');

// Independent Python checks execute on their owning runners. The verification
// core validates their same-run receipts at the normal Gradle task boundaries.
const groups = {
  'detached-retarget': [':blue-conformance:legalDetachedRetargetReleaseTransitionTest'],
  reconciliation: [':blue-conformance:baselineReconciliationReleaseTransitionTest'],
  'witness-context': [':blue-conformance:witnessContextReleaseTransitionTest'],
  'inactive-retarget': [':blue-conformance:inactiveRetargetReleaseTransitionTest'],
  'short-transitions': [
    ':blue-conformance:witnessSelectionReleaseTransitionTest',
    ':blue-conformance:baselineC03DeferralReleaseTransitionTest',
  ],
};
function commandsFor(group) {
  if (Object.hasOwn(groups, group)) return [groups[group]];
  throw new Error(`Unknown transition group: ${group}`);
}

function attemptNumber(value) {
  if (!/^[1-9][0-9]*$/.test(String(value)) || !Number.isSafeInteger(Number(value))) {
    throw new Error('Invalid workflow attempt');
  }
  return Number(value);
}

// Owner names include the source scope; nested reusable workflows add prefixes.
function selectOwnerAttempt(jobs, scope, group, sourceAttempt, consumerAttempt) {
  const first = attemptNumber(sourceAttempt);
  const last = attemptNumber(consumerAttempt);
  if (first > last || !/^[a-z0-9-]+$/.test(scope) || !Object.hasOwn(groups, group)) {
    throw new Error('Invalid owner identity');
  }
  const name = `Python ${scope} / ${group}`;
  const candidates = jobs.filter(job => (job.name === name || job.name.endsWith(` / ${name}`))
    && job.run_attempt >= first && job.run_attempt <= last);
  if (!candidates.length) return null;
  const newest = Math.max(...candidates.map(job => attemptNumber(job.run_attempt)));
  const latest = candidates.filter(job => Number(job.run_attempt) === newest);
  if (latest.length !== 1) throw new Error('Ambiguous transition owner');
  const owner = latest[0];
  if (owner.status !== 'completed') return null;
  if (owner.conclusion === 'success') return String(newest);
  // A failed previous attempt may be awaiting creation of its rerun job.
  if (newest < last) return null;
  throw new Error(`Transition owner failed: ${name} (${owner.conclusion})`);
}

function sourceIdentity() {
  execFileSync('git', ['diff', '--quiet', 'HEAD', '--']);
  return {
    commit: execFileSync('git', ['rev-parse', 'HEAD'], {encoding: 'utf8'}).trim(),
    sourceTree: execFileSync('git', ['rev-parse', 'HEAD^{tree}'], {encoding: 'utf8'}).trim(),
    runId: process.env.GITHUB_RUN_ID || 'local',
    runAttempt: process.env.GITHUB_RUN_ATTEMPT || 'local',
  };
}

function validatePreparedIdentity(manifest, trusted, checkout) {
  for (const key of ['eventCommit', 'commit', 'sourceTree', 'parentCommit', 'runId', 'runAttempt', 'scope', 'prepared']) {
    if (manifest[key] !== trusted[key]) throw new Error(`Mismatched prepared ${key}`);
  }
  for (const key of ['eventCommit', 'commit', 'sourceTree', 'runId', 'runAttempt', 'scope']) {
    if (!manifest[key]) throw new Error(`Missing prepared ${key}`);
  }
  if (typeof manifest.prepared !== 'boolean'
      || (manifest.prepared ? manifest.parentCommit !== manifest.eventCommit
        : manifest.commit !== manifest.eventCommit)) throw new Error('Invalid prepared source linkage');
  if (attemptNumber(checkout.runAttempt) < attemptNumber(manifest.runAttempt)) {
    throw new Error('Consumer predates prepared source attempt');
  }
  for (const key of ['commit', 'sourceTree', 'runId']) {
    if (manifest[key] !== checkout[key]) throw new Error(`Mismatched checkout ${key}`);
  }
}

function validateReport(report, group, identity) {
  if (report.group !== group || report.success !== true) throw new Error(`Failed group: ${group}`);
  for (const key of ['commit', 'sourceTree', 'runId', 'runAttempt', 'scope', 'sourceAttempt']) {
    if (!identity[key] || report[key] !== identity[key]) throw new Error(`Mismatched ${key}: ${group}`);
  }
  if (!Number.isFinite(report.elapsedSeconds) || report.elapsedSeconds <= 0) throw new Error('Invalid duration');
  if (!Array.isArray(report.commands)
      || JSON.stringify(report.commands.map(c => c.args)) !== JSON.stringify(commandsFor(group))
      || report.commands.some(c => c.exitCode !== 0 || !Number.isFinite(c.elapsedSeconds) || c.elapsedSeconds <= 0)) {
    throw new Error(`Incomplete or failed commands: ${group}`);
  }
}

function validateReceipt(report, group, task, identity) {
  if (!Object.hasOwn(groups, group) || !groups[group].includes(task)) throw new Error('Unknown transition assignment');
  validateReport(report, group, identity);
}

function consumeReceipt(group, task, directory) {
  const manifest = require('./ci-verification-source').check();
  const identity = sourceIdentity();
  if (!Object.hasOwn(groups, group) || !groups[group].includes(task)) throw new Error('Unknown transition assignment');
  const scope = manifest.scope;
  // The enclosing core job has a 90-minute budget. Do not impose a shorter
  // receipt deadline: owners can spend time queued before their 30-minute job.
  for (;;) {
    const pages = JSON.parse(execFileSync('gh', ['api', '--paginate', '--slurp',
      `repos/${process.env.GITHUB_REPOSITORY}/actions/runs/${identity.runId}/jobs?filter=all&per_page=100`],
      {encoding: 'utf8', timeout: 60000}));
    const ownerAttempt = selectOwnerAttempt(pages.flatMap(page => page.jobs), scope, group,
      manifest.runAttempt, identity.runAttempt);
    if (ownerAttempt) {
      const target = path.join(directory, scope, `attempt-${ownerAttempt}`, group);
      const file = path.join(target, `${group}.json`);
      if (!fs.existsSync(file)) {
        fs.mkdirSync(target, {recursive: true});
        const download = spawnSync('gh', ['run', 'download', identity.runId,
          '--repo', process.env.GITHUB_REPOSITORY,
          '--name', `verification-${scope}-${ownerAttempt}-${group}`, '--dir', target],
          {encoding: 'utf8', timeout: 60000});
        if (download.status !== 0 || !fs.existsSync(file)) {
          fs.rmSync(target, {recursive: true, force: true});
          throw new Error(`Cannot download successful owner receipt: ${download.stderr || download.error}`);
        }
      }
      validateReceipt(JSON.parse(fs.readFileSync(file, 'utf8')), group, task,
        {...identity, runAttempt: ownerAttempt, scope, sourceAttempt: manifest.runAttempt});
      console.log(`Verified remote execution of ${task}: run ${identity.runId}, owner attempt ${ownerAttempt}, commit ${identity.commit}`);
      return;
    }
    console.log(`Waiting for same-source transition owner: ${scope}/${group}`);
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 15000);
  }
}

function run(group, reportDirectory) {
  const manifest = require('./ci-verification-source').check();
  const commands = commandsFor(group);
  const identity = sourceIdentity();
  const started = performance.now();
  const report = {group, ...identity, scope: manifest.scope, sourceAttempt: manifest.runAttempt, startedAtMs: Date.now(), success: false, commands: []};
  try {
    for (const args of commands) {
      console.log(`::group::./gradlew ${args.join(' ')}`);
      const commandStarted = performance.now();
      const result = spawnSync('./gradlew', args, {stdio: 'inherit'});
      report.commands.push({args, exitCode: result.status,
        elapsedSeconds: (performance.now() - commandStarted) / 1000});
      console.log('::endgroup::');
      if (result.error || result.status !== 0) throw new Error(`Gradle failed for ${group}: ${result.error || result.status}`);
    }
    if (JSON.stringify(sourceIdentity()) !== JSON.stringify(identity)) throw new Error('Source identity changed during verification');
    report.success = true;
  } finally {
    report.elapsedSeconds = (performance.now() - started) / 1000;
    report.finishedAtMs = Date.now();
    fs.mkdirSync(reportDirectory, {recursive: true});
    fs.writeFileSync(path.join(reportDirectory, `${group}.json`), JSON.stringify(report, null, 2) + '\n');
  }
}

module.exports = {sourceIdentity, validatePreparedIdentity, groups, commandsFor, validateReceipt, selectOwnerAttempt};

if (require.main === module) {
  try {
    const [mode, argument, directory] = process.argv.slice(2);
    if (mode === 'assignments') console.log(JSON.stringify(groups));
    else if (mode === 'run') {
      if (!directory) throw new Error('Report directory is required');
      run(argument, directory);
    } else if (mode === 'receipt') {
      if (!process.env.CI_TRANSITION_RECEIPTS) throw new Error('CI_TRANSITION_RECEIPTS is required');
      consumeReceipt(argument, directory, process.env.CI_TRANSITION_RECEIPTS);
    } else throw new Error('Expected assignments, run or receipt');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
