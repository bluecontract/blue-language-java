const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { execFileSync, spawnSync } = require('node:child_process');

// These independent Python checks have no build outputs consumed by other tasks.
// Leave the normal Gradle lifecycle intact; exclude them only in this experiment's
// core job, whose result is insufficient without every parallel group succeeding.
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
    ['clean', 'build', 'rcVerify', 'verifyFinalApiBaseline',
      ...Object.values(groups).flat().flatMap(task => ['-x', task])],
    ['sourceReleaseArchive', '--rerun-tasks'],
  ];
  if (Object.hasOwn(groups, group)) return [groups[group]];
  throw new Error(`Unknown experiment group: ${group}`);
}

function sourceArchiveHash() {
  const directory = path.resolve('build/release');
  const archives = fs.readdirSync(directory).filter(name => name.endsWith('-source-release.zip'));
  if (archives.length !== 1) throw new Error('Expected exactly one source release archive');
  return createHash('sha256').update(fs.readFileSync(path.join(directory, archives[0]))).digest('hex');
}

function run(group, reportDirectory) {
  const commands = commandsFor(group);
  const commit = execFileSync('git', ['rev-parse', 'HEAD'], {encoding: 'utf8'}).trim();
  const started = performance.now();
  const report = {group, commit, success: false, commands: []};
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
    report.success = true;
  } finally {
    report.elapsedSeconds = (performance.now() - started) / 1000;
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
  const baseline = byGroup.get('baseline').elapsedSeconds;
  const parallel = Math.max(...reports.filter(r => r.group !== 'baseline').map(r => r.elapsedSeconds));
  const rows = groupNames.map(group => `| ${group} | ${byGroup.get(group).elapsedSeconds.toFixed(1)} |`);
  return [
    '## Verification timing experiment — no remote publication', '',
    '| Group | Command wall time (seconds) |', '| --- | ---: |', ...rows, '',
    `Baseline: **${baseline.toFixed(1)} s**. Longest parallel group: **${parallel.toFixed(1)} s**.`,
    `Estimated reduction from overlapping these groups: **${((1 - parallel / baseline) * 100).toFixed(1)}%**.`, '',
    'These measurements exclude runner queue, checkout, setup and artifact transfer. They are not total release times.',
    'All groups passed on the same commit. Inspect job timings for actual scheduling overlap.',
    'Normal build and release workflows are unchanged. No package, release, tag or version was published.', '',
  ].join('\n');
}

if (require.main === module) {
  try {
    const [mode, argument, directory] = process.argv.slice(2);
    if (mode === 'matrix') console.log(JSON.stringify({group: groupNames}));
    else if (mode === 'run') {
      if (!directory) throw new Error('Report directory is required');
      run(argument, directory);
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

module.exports = {groups, commandsFor, summarize};
