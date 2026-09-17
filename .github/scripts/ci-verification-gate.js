const {groups} = require('./ci-transition-verification');
const {execFileSync} = require('node:child_process');
for (const [group, tasks] of Object.entries(groups)) {
  for (const task of tasks) execFileSync(process.execPath,
    ['.github/scripts/ci-transition-verification.js', 'receipt', group, task], {stdio: 'inherit'});
}
