#!/usr/bin/env node

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');

const CZ_TOML = '.cz.toml';
const BASE_REF = process.env.RC_BASE_REF || 'origin/master';
const VALID_BUMPS = new Set(['major', 'minor', 'patch']);

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8' }).trim();
}

function readVersion(content) {
  const match = content.match(/^version\s*=\s*"([^"]+)"/m);
  if (!match) {
    throw new Error('Could not find version in .cz.toml');
  }
  return match[1];
}

function parseVersion(version) {
  const match = version.match(/^(\d+)\.(\d+)\.(\d+)(?:-rc\.\d+)?$/);
  if (!match) {
    throw new Error(`Unsupported version format: ${version}`);
  }
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
  };
}

function formatVersion(version) {
  return `${version.major}.${version.minor}.${version.patch}`;
}

function increment(version, bump) {
  if (bump === 'major') {
    return { major: version.major + 1, minor: 0, patch: 0 };
  }
  if (bump === 'minor') {
    return { major: version.major, minor: version.minor + 1, patch: 0 };
  }
  return { major: version.major, minor: version.minor, patch: version.patch + 1 };
}

function commitMessagesSince(ref) {
  return git(['log', '--format=%B%x00', `${ref}..HEAD`])
    .split('\0')
    .map((message) => message.trim())
    .filter(Boolean)
    .filter((message) => !message.startsWith('chore: release '));
}

function bumpFromMessages(messages) {
  const explicit = process.env.RC_BUMP;
  if (explicit) {
    if (!VALID_BUMPS.has(explicit)) {
      throw new Error(`RC_BUMP must be one of: ${Array.from(VALID_BUMPS).join(', ')}`);
    }
    return explicit;
  }

  let bump = null;
  for (const message of messages) {
    const header = message.split(/\r?\n/, 1)[0];
    if (/BREAKING CHANGE:/m.test(message) || /^[a-zA-Z]+(?:\([^)]*\))?!:/.test(header)) {
      return 'major';
    }
    if (/^feat(?:\([^)]*\))?:/.test(header)) {
      bump = bump === 'patch' || bump == null ? 'minor' : bump;
    } else if (/^fix(?:\([^)]*\))?:/.test(header) && bump == null) {
      bump = 'patch';
    } else if (bump == null) {
      bump = 'patch';
    }
  }

  return bump || 'patch';
}

function latestRc(base) {
  const output = git(['tag', '--list', `v${base}-rc.*`]);
  if (!output) {
    return 0;
  }
  return output
    .split(/\r?\n/)
    .map((tag) => tag.match(new RegExp(`^v${base.replaceAll('.', '\\.')}-rc\\.(\\d+)$`)))
    .filter(Boolean)
    .map((match) => Number(match[1]))
    .reduce((max, value) => Math.max(max, value), 0);
}

function versionFromRef(ref) {
  const content = git(['show', `${ref}:${CZ_TOML}`]);
  return parseVersion(readVersion(content));
}

const currentContent = fs.readFileSync(CZ_TOML, 'utf8');
const baseVersion = versionFromRef(BASE_REF);
const messages = commitMessagesSince(BASE_REF);
const bump = bumpFromMessages(messages);
const targetBase = formatVersion(increment(baseVersion, bump));
const nextVersion = `${targetBase}-rc.${latestRc(targetBase) + 1}`;

const nextContent = currentContent.replace(
  /^version\s*=\s*"[^"]+"/m,
  `version = "${nextVersion}"`,
);
fs.writeFileSync(CZ_TOML, nextContent);

console.log(`Base ref: ${BASE_REF}`);
console.log(`Base version: ${formatVersion(baseVersion)}`);
console.log(`Aggregate bump: ${bump}`);
console.log(`Next RC version: ${nextVersion}`);

if (process.env.GITHUB_OUTPUT) {
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${nextVersion}\n`);
}
