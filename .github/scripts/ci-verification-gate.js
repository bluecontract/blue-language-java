const {groups} = require('./ci-timing-experiment');
const {execFileSync} = require('node:child_process');
for (const [group, tasks] of Object.entries(groups)) {
  for (const task of tasks) execFileSync(process.execPath,
    ['.github/scripts/ci-timing-experiment.js', 'receipt', group, task], {stdio: 'inherit'});
}
