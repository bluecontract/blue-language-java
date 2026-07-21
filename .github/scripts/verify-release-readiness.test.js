const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  readVersion,
  validateReleaseReadiness,
  verifyPreparedRelease,
} = require('./verify-release-readiness');

function validInput(overrides = {}) {
  return {
    expectedVersion: '3.1.0-rc.16',
    currentVersion: '3.1.0-rc.16',
    releaseChannel: 'rc',
    existingTag: '',
    changedPaths: ['.cz.toml'],
    githubActions: true,
    githubRef: 'refs/heads/next',
    ...overrides,
  };
}

test('reads the Commitizen version', () => {
  assert.equal(readVersion('[tool.commitizen]\nversion = "3.1.0-rc.16"\n'), '3.1.0-rc.16');
  assert.throws(() => readVersion('[tool.commitizen]\n'), /Could not find a version/);
});

test('accepts a freshly prepared RC on next', () => {
  assert.deepEqual(validateReleaseReadiness(validInput()), []);
});

test('rejects missing or malformed release metadata', () => {
  assert.match(
    validateReleaseReadiness(validInput({ expectedVersion: '' })).join('\n'),
    /RELEASE_VERSION is required/,
  );
  assert.match(
    validateReleaseReadiness(validInput({ expectedVersion: '3.1.0' })).join('\n'),
    /must match x\.y\.z-rc\.n/,
  );
  assert.match(
    validateReleaseReadiness(validInput({ releaseChannel: 'stable' })).join('\n'),
    /BLUE_RELEASE_CHANNEL must be 'rc'/,
  );
  assert.match(
    validateReleaseReadiness(validInput({ currentVersion: '3.1.0-rc.15' })).join('\n'),
    /.cz.toml version must be/,
  );
});

test('rejects an existing release tag', () => {
  assert.match(
    validateReleaseReadiness(validInput({ existingTag: 'v3.1.0-rc.16' })).join('\n'),
    /already exists/,
  );
});

test('rejects preparation side effects outside .cz.toml', () => {
  assert.match(
    validateReleaseReadiness(
      validInput({ changedPaths: ['.cz.toml', 'CHANGELOG.md'] }),
    ).join('\n'),
    /CHANGELOG.md/,
  );
});

test('rejects an Actions release from a branch other than next', () => {
  assert.match(
    validateReleaseReadiness(validInput({ githubRef: 'refs/heads/master' })).join('\n'),
    /must run from refs\/heads\/next/,
  );
});

test('verifies a prepared RC in a clean temporary repository', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'blue-rc-readiness-'));
  try {
    execFileSync('git', ['init'], { cwd: directory });
    execFileSync('git', ['config', 'user.name', 'Release Test'], { cwd: directory });
    execFileSync('git', ['config', 'user.email', 'release-test@example.invalid'], {
      cwd: directory,
    });
    fs.writeFileSync(
      path.join(directory, '.cz.toml'),
      '[tool.commitizen]\nversion = "3.1.0-rc.15"\n',
    );
    execFileSync('git', ['add', '.cz.toml'], { cwd: directory });
    execFileSync('git', ['commit', '-m', 'initial'], { cwd: directory });
    fs.writeFileSync(
      path.join(directory, '.cz.toml'),
      '[tool.commitizen]\nversion = "3.1.0-rc.16"\n',
    );

    assert.equal(
      verifyPreparedRelease({
        cwd: directory,
        env: {
          RELEASE_VERSION: '3.1.0-rc.16',
          BLUE_RELEASE_CHANNEL: 'rc',
        },
      }),
      '3.1.0-rc.16',
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
