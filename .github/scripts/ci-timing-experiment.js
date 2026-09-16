const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { execFileSync, spawnSync } = require('node:child_process');

// Independent Python checks execute on their owning runners. The experimental
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
const groupNames = ['baseline', 'core', ...Object.keys(groups)];

function commandsFor(group) {
  if (group === 'baseline') return [
    ['clean', 'build'], ['rcVerify'],
    ['sourceReleaseArchive', '--rerun-tasks'], ['verifyFinalApiBaseline'],
  ];
  if (group === 'core') return [
    ['--init-script', '.github/scripts/ci-transition-receipts.init.gradle', 'clean', 'build'],
    ['--init-script', '.github/scripts/ci-transition-receipts.init.gradle', 'rcVerify'],
    ['sourceReleaseArchive', '--rerun-tasks'],
    ['verifyFinalApiBaseline'],
  ];
  if (Object.hasOwn(groups, group)) return [groups[group]];
  throw new Error(`Unknown experiment group: ${group}`);
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

function validateReport(report, group, identity) {
  if (report.group !== group || report.success !== true) throw new Error(`Failed group: ${group}`);
  for (const key of ['commit', 'sourceTree', 'runId', 'runAttempt']) {
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
  const identity = sourceIdentity();
  if (process.env.GITHUB_REF !== 'refs/heads/codex/ci/language-parallel-experiment'
      || process.env.EXPERIMENT_GROUP !== 'core'
      || identity.runId === 'local' || identity.runAttempt === 'local'
      || process.env.GITHUB_SHA !== identity.commit) throw new Error('Receipt import requires the isolated CI run');
  if (!Object.hasOwn(groups, group) || !groups[group].includes(task)) throw new Error('Unknown transition assignment');
  const target = path.join(directory, `attempt-${identity.runAttempt}`, group);
  const file = path.join(target, `${group}.json`);
  const deadline = Date.now() + 25 * 60 * 1000;
  while (!fs.existsSync(file)) {
    fs.mkdirSync(target, {recursive: true});
    const download = spawnSync('gh', ['run', 'download', identity.runId,
      '--repo', process.env.GITHUB_REPOSITORY,
      '--name', `timing-${identity.runAttempt}-${group}`, '--dir', target],
      {encoding: 'utf8', timeout: 60000});
    if (download.status === 0 && fs.existsSync(file)) break;
    // Failed/partial downloads must not leave a file accepted on the next loop.
    fs.rmSync(target, {recursive: true, force: true});
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${group}: ${download.stderr || download.error}`);
    console.log(`Waiting for same-run test results: ${group}`);
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 15000);
  }
  validateReceipt(JSON.parse(fs.readFileSync(file, 'utf8')), group, task, identity);
  console.log(`Verified remote execution of ${task}: run ${identity.runId}, attempt ${identity.runAttempt}, commit ${identity.commit}`);
}

function sourceArchiveHash() {
  const directory = path.resolve('build/release');
  const archives = fs.readdirSync(directory).filter(name => name.endsWith('-source-release.zip'));
  if (archives.length !== 1) throw new Error('Expected exactly one source release archive');
  return createHash('sha256').update(fs.readFileSync(path.join(directory, archives[0]))).digest('hex');
}

function run(group, reportDirectory) {
  const commands = commandsFor(group);
  const identity = sourceIdentity();
  const started = performance.now();
  const report = {group, ...identity, startedAtMs: Date.now(), success: false, commands: []};
  try {
    for (const args of commands) {
      const repeatedArchive = args.includes('--rerun-tasks');
      const before = repeatedArchive ? sourceArchiveHash() : null;
      console.log(`::group::./gradlew ${args.join(' ')}`);
      const commandStarted = performance.now();
      const result = spawnSync('./gradlew', args, {stdio: 'inherit'});
      report.commands.push({args, exitCode: result.status,
        elapsedSeconds: (performance.now() - commandStarted) / 1000});
      console.log('::endgroup::');
      if (result.error || result.status !== 0) throw new Error(`Gradle failed for ${group}: ${result.error || result.status}`);
      if (repeatedArchive && sourceArchiveHash() !== before) throw new Error('Source archive is not reproducible');
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

function summarize(reports) {
  const byGroup = new Map();
  for (const report of reports) {
    if (!groupNames.includes(report.group)) throw new Error('Unknown report group');
    if (byGroup.has(report.group)) throw new Error(`Duplicate report: ${report.group}`);
    if (report.success !== true) throw new Error(`Failed group: ${report.group}`);
    if (!Number.isFinite(report.elapsedSeconds) || report.elapsedSeconds <= 0) throw new Error('Invalid duration');
    byGroup.set(report.group, report);
  }
  for (const group of groupNames) if (!byGroup.has(group)) throw new Error(`Missing report: ${group}`);
  if (new Set(reports.map(r => r.commit)).size !== 1 || !reports[0].commit) throw new Error('Reports must use the same commit');
  for (const group of groupNames) validateReport(byGroup.get(group), group, reports[0]);
  const baseline = byGroup.get('baseline').elapsedSeconds;
  const parallelReports = reports.filter(r => r.group !== 'baseline');
  if (reports.some(r => !Number.isFinite(r.startedAtMs) || !Number.isFinite(r.finishedAtMs)
      || r.finishedAtMs <= r.startedAtMs)) throw new Error('Missing command timestamps');
  const parallel = (Math.max(...parallelReports.map(r => r.finishedAtMs))
    - Math.min(...parallelReports.map(r => r.startedAtMs))) / 1000;
  const rows = groupNames.map(group => `| ${group} | ${byGroup.get(group).elapsedSeconds.toFixed(1)} |`);
  return [
    '## Verification timing experiment — no remote publication', '',
    '| Group | Command wall time (seconds) |', '| --- | ---: |', ...rows, '',
    `Baseline: **${baseline.toFixed(1)} s**. Parallel command window: **${parallel.toFixed(1)} s**.`,
    `Measured command-window reduction: **${((1 - parallel / baseline) * 100).toFixed(1)}%**.`, '',
    'These are command-window timings, not total release times. Initial setup and final report uploads are outside the measurement; receipt download waits are included.',
    'All commands and groups passed on the same commit, source tree and workflow attempt. Core time includes receipt waits.',
    'This is a distributed CI experiment; it does not qualify artifacts for publication by the production release workflows.',
    'Normal build and release workflows are unchanged. No package, release, tag or version was published.', '',
  ].join('\n');
}

if (require.main === module) {
  try {
    const [mode, argument, directory] = process.argv.slice(2);
    if (mode === 'matrix') console.log(JSON.stringify({group: groupNames}));
    else if (mode === 'assignments') console.log(JSON.stringify(groups));
    else if (mode === 'run') {
      if (!directory) throw new Error('Report directory is required');
      run(argument, directory);
    } else if (mode === 'receipt') {
      const receiptDirectory = process.env.CI_TRANSITION_RECEIPTS;
      if (!receiptDirectory) throw new Error('CI_TRANSITION_RECEIPTS is required');
      consumeReceipt(argument, directory, receiptDirectory);
    } else if (mode === 'summary') {
      const reports = fs.readdirSync(argument).filter(name => name.endsWith('.json'))
        .map(name => JSON.parse(fs.readFileSync(path.join(argument, name), 'utf8')));
      const markdown = summarize(reports);
      console.log(markdown);
      if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown);
    } else throw new Error('Expected matrix, run or summary');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = {groups, commandsFor, summarize, validateReceipt};
