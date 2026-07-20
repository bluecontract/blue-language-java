#!/usr/bin/env node

const fs = require('node:fs');

const REPORT = 'docs/performance/results/order-scale-language-summary.json';
const expectedVersion = process.env.RELEASE_VERSION;

if (!expectedVersion) {
  throw new Error('RELEASE_VERSION is required');
}

const summary = JSON.parse(fs.readFileSync(REPORT, 'utf8'));
const candidate = summary.candidate || {};
const expectedCoordinate = `blue.language:blue-language-java:${expectedVersion}`;
const failures = [];

if (candidate.coordinate !== expectedCoordinate) {
  failures.push(`candidate coordinate must be ${expectedCoordinate}`);
}
if (candidate.releaseReady !== true) {
  failures.push('candidate.releaseReady must be true');
}
if (candidate.verdict !== 'YES') {
  failures.push('candidate.verdict must be YES');
}

if (failures.length > 0) {
  console.error(`Release authorization failed in ${REPORT}:`);
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(`Release authorization confirmed for ${expectedCoordinate}`);
