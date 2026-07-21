#!/usr/bin/env node

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const CZ_TOML = '.cz.toml';
const RC_VERSION = /^\d+\.\d+\.\d+-rc\.\d+$/;

function readVersion(content) {
  const match = content.match(/^version\s*=\s*"([^"]+)"/m);
  if (!match) {
    throw new Error(`Could not find a version in ${CZ_TOML}`);
  }
  return match[1];
}

function validateReleaseReadiness({
  expectedVersion,
  currentVersion,
  releaseChannel,
  existingTag,
  changedPaths = [],
  githubActions = false,
  githubRef,
}) {
  const failures = [];

  if (!expectedVersion) {
    failures.push('RELEASE_VERSION is required');
  } else if (!RC_VERSION.test(expectedVersion)) {
    failures.push(`RELEASE_VERSION must match x.y.z-rc.n, found '${expectedVersion}'`);
  }

  if (releaseChannel !== 'rc') {
    failures.push(`BLUE_RELEASE_CHANNEL must be 'rc', found '${releaseChannel || ''}'`);
  }

  if (expectedVersion && currentVersion !== expectedVersion) {
    failures.push(
      `${CZ_TOML} version must be '${expectedVersion}', found '${currentVersion || ''}'`,
    );
  }

  if (existingTag) {
    failures.push(`release tag '${existingTag}' already exists`);
  }

  const unexpectedPaths = changedPaths.filter((path) => path && path !== CZ_TOML);
  if (unexpectedPaths.length > 0) {
    failures.push(
      `RC preparation changed files other than ${CZ_TOML}: ${unexpectedPaths.join(', ')}`,
    );
  }

  if (githubActions && githubRef !== 'refs/heads/next') {
    failures.push(`RC workflow must run from refs/heads/next, found '${githubRef || ''}'`);
  }

  return failures;
}

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

function changedTrackedPaths(cwd) {
  const status = execFileSync(
    'git',
    ['status', '--porcelain', '--untracked-files=no'],
    { cwd, encoding: 'utf8' },
  ).replace(/\r?\n$/, '');
  if (!status) {
    return [];
  }
  return status.split(/\r?\n/).map((line) => {
    const path = line.slice(3).trim();
    const renameSeparator = path.lastIndexOf(' -> ');
    return renameSeparator >= 0 ? path.slice(renameSeparator + 4) : path;
  });
}

function verifyPreparedRelease({ cwd = process.cwd(), env = process.env } = {}) {
  const expectedVersion = env.RELEASE_VERSION || '';
  const currentVersion = readVersion(fs.readFileSync(path.join(cwd, CZ_TOML), 'utf8'));
  const existingTag = expectedVersion
    ? git(['tag', '--list', `v${expectedVersion}`], cwd)
    : '';
  const failures = validateReleaseReadiness({
    expectedVersion,
    currentVersion,
    releaseChannel: env.BLUE_RELEASE_CHANNEL,
    existingTag,
    changedPaths: changedTrackedPaths(cwd),
    githubActions: env.GITHUB_ACTIONS === 'true',
    githubRef: env.GITHUB_REF,
  });

  if (failures.length > 0) {
    throw new Error(`RC release preflight failed:\n- ${failures.join('\n- ')}`);
  }

  return expectedVersion;
}

function main() {
  try {
    const version = verifyPreparedRelease();
    console.log(`RC release metadata verified for blue.language:blue-language-java:${version}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  readVersion,
  validateReleaseReadiness,
  verifyPreparedRelease,
};
