#!/usr/bin/env node

const fs = require('node:fs');

if (process.argv.length < 5) {
  throw new Error(
    'Usage: sanitize-jmh-results.js <output.json> <input.json> <input.json> [...]',
  );
}

const output = process.argv[2];
const inputs = process.argv.slice(3);
const results = inputs.flatMap((input) => JSON.parse(fs.readFileSync(input, 'utf8')));

for (const result of results) {
  result.jvm = '<local-java-path-redacted>';
  result.evidenceStatus = 'NON_FORKED_DIAGNOSTIC_ONLY';
  result.releaseGateEvidence = false;
}

fs.writeFileSync(output, `${JSON.stringify(results, null, 2)}\n`);
