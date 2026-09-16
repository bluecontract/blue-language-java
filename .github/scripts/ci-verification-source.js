// Transfer one source commit to every verification runner, without a remote push.
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {sourceIdentity, validatePreparedIdentity} = require('./ci-timing-experiment');
const git = (...args) => execFileSync('git', args, {encoding: 'utf8'}).trim();
const output = (key, value) => fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);

function trustedIdentity() {
  const env = process.env;
  if (env.BLUE_CI_DISTRIBUTED !== 'verified-source' || !/^[a-z0-9-]+$/.test(env.BLUE_CI_SCOPE || '')
      || !env.GITHUB_RUN_ID || !env.GITHUB_RUN_ATTEMPT || !env.GITHUB_SHA) throw new Error('Distributed CI context required');
  if (!['true', 'false'].includes(env.BLUE_CI_PREPARED)) throw new Error('Expected prepared flag');
  return {eventCommit: env.GITHUB_SHA, commit: env.BLUE_CI_COMMIT, sourceTree: env.BLUE_CI_TREE,
    parentCommit: env.BLUE_CI_PREPARED === 'true' ? env.GITHUB_SHA : '', prepared: env.BLUE_CI_PREPARED === 'true',
    runId: env.GITHUB_RUN_ID, runAttempt: env.GITHUB_RUN_ATTEMPT, scope: env.BLUE_CI_SCOPE};
}

function check() {
  const trusted = trustedIdentity();
  const manifest = JSON.parse(fs.readFileSync(path.join(process.env.BLUE_CI_SOURCE, 'identity.json'), 'utf8'));
  validatePreparedIdentity(manifest, trusted, sourceIdentity());
  if (manifest.prepared) {
    if (git('rev-parse', 'HEAD^') !== manifest.eventCommit) throw new Error('Prepared commit has wrong parent');
    if (git('diff-tree', '--no-commit-id', '--name-only', '-r', 'HEAD') !== '.cz.toml') {
      throw new Error('Preparation must change only .cz.toml');
    }
  }
  return manifest;
}

function prepare(directory, mode) {
  const eventCommit = process.env.GITHUB_SHA;
  if (!['existing', 'rc', 'fixture'].includes(mode) || git('rev-parse', 'HEAD') !== eventCommit) {
    throw new Error('Preparation requires the event checkout and a known mode');
  }
  git('diff', '--quiet', 'HEAD', '--');
  const scope = process.env.BLUE_CI_SCOPE;
  if (!/^[a-z0-9-]+$/.test(scope || '') || !process.env.GITHUB_RUN_ID || !process.env.GITHUB_RUN_ATTEMPT) {
    throw new Error('Preparation requires workflow identity');
  }
  if (mode === 'rc') {
    if (process.env.GITHUB_REF !== 'refs/heads/next') throw new Error('RC preparation requires next');
    execFileSync(process.execPath, ['.github/scripts/prepare-rc-release.js'], {stdio: 'inherit'});
    const version = fs.readFileSync('.cz.toml', 'utf8').match(/^version\s*=\s*"([^"]+)"/m)[1];
    execFileSync(process.execPath, ['.github/scripts/verify-release-readiness.js'], {
      stdio: 'inherit', env: {...process.env, RELEASE_VERSION: version, BLUE_RELEASE_CHANNEL: 'rc'},
    });
    git('add', '.cz.toml');
    git('commit', '-m', `chore: release ${version}`);
  } else if (mode === 'fixture') {
    if (process.env.GITHUB_REF !== 'refs/heads/codex/ci/language-parallel-experiment') {
      throw new Error('Prepared-source fixture requires the experiment branch');
    }
    // Exercise changed tree/commit transfer without assigning any new RC version.
    fs.appendFileSync('.cz.toml', '\n# Isolated CI source-transfer verification.\n');
    git('add', '.cz.toml');
    git('commit', '-m', 'ci: exercise prepared source transfer');
  }
  const manifest = {...sourceIdentity(), eventCommit, scope, prepared: mode !== 'existing',
    parentCommit: mode === 'existing' ? '' : eventCommit};
  fs.mkdirSync(directory, {recursive: true});
  git('bundle', 'create', path.join(directory, 'source.bundle'), 'HEAD');
  fs.writeFileSync(path.join(directory, 'identity.json'), JSON.stringify(manifest, null, 2) + '\n');
  output('commit', manifest.commit);
  output('tree', manifest.sourceTree);
  output('prepared', String(manifest.prepared));
  output('version', fs.readFileSync('.cz.toml', 'utf8').match(/^version\s*=\s*"([^"]+)"/m)[1]);
}

function restore() {
  const directory = process.env.BLUE_CI_SOURCE;
  // Expected commit/tree come from prepare job outputs, not downloaded files.
  trustedIdentity();
  git('bundle', 'verify', path.join(directory, 'source.bundle'));
  git('fetch', path.join(directory, 'source.bundle'), 'HEAD');
  if (git('rev-parse', 'FETCH_HEAD') !== process.env.BLUE_CI_COMMIT) throw new Error('Unexpected bundle HEAD');
  git('checkout', '--detach', 'FETCH_HEAD');
  const manifest = check();
  fs.appendFileSync(process.env.GITHUB_ENV, `SOURCE_DATE_EPOCH=${git('show', '-s', '--format=%ct', manifest.commit)}\n`);
}

if (require.main === module) {
  try {
    const [command, directory, mode] = process.argv.slice(2);
    if (command === 'prepare') prepare(directory, mode);
    else if (command === 'restore') restore();
    else if (command === 'check') check();
    else throw new Error('Expected prepare, restore or check');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {check, prepare, restore};
