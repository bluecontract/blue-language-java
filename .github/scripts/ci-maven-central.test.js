const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

for (const file of ['release-rc.yml', 'release.yml']) {
  test(`${file} confirms Maven publication before completing the GitHub release`, () => {
    const workflow = fs.readFileSync(path.join(__dirname, '../workflows', file), 'utf8');
    const submit = workflow.indexOf('./gradlew jreleaserDeploy -PblueMavenCentralSkipPublicationCheck=true');
    const snapshot = workflow.indexOf('cp build/jreleaser/output.properties build/jreleaser/maven-central-submitted.properties');
    const wait = workflow.indexOf('python .github/scripts/wait-maven-central.py');
    const finish = workflow.indexOf('./gradlew jreleaserFullRelease --exclude-deployer-name=sonatype');
    assert.doesNotMatch(workflow, /--exclude-deployer=/, 'JReleaser 1.24 type normalization does not match mavenCentral');
    const build = fs.readFileSync(path.join(__dirname, '../../build.gradle'), 'utf8');
    assert.match(build, /mavenCentral\s*\{\s*sonatype\s*\{/, 'excluded name matches the configured Maven Central deployer');
    assert.ok(submit > 0, 'submission is a separate deployment phase');
    assert.ok(submit < snapshot && snapshot < wait && wait < finish, 'PUBLISHED confirmation precedes GitHub release');
    assert.match(workflow.slice(0, submit), /rm -f build\/jreleaser\/output\.properties build\/jreleaser\/maven-central-published\.json build\/jreleaser\/maven-central-submitted\.properties/);
    const waitStep = workflow.slice(workflow.lastIndexOf('      - name:', wait), finish);
    assert.match(waitStep, /name: Wait for Maven Central publication/);
    assert.match(waitStep, /JRELEASER_MAVENCENTRAL_USERNAME:/);
    assert.match(waitStep, /JRELEASER_MAVENCENTRAL_PASSWORD:/);
    assert.match(waitStep, /wait-maven-central\.py build\/jreleaser\/maven-central-submitted\.properties/);
    assert.match(waitStep, /--receipt build\/jreleaser\/maven-central-published\.json/);
    assert.doesNotMatch(waitStep, /continue-on-error:\s*true|if:\s*always\(\)/);
    assert.equal([...workflow.matchAll(/run: \.\/gradlew jreleaserFullRelease/g)].length, 1);
  });
}

test('publication polling is skipped only for the explicitly split deployment invocation', () => {
  const build = fs.readFileSync(path.join(__dirname, '../../build.gradle'), 'utf8');
  assert.match(build, /skipPublicationCheck = providers\.gradleProperty\('blueMavenCentralSkipPublicationCheck'\)\s*\.map \{ it\.toBoolean\(\) \}\.getOrElse\(false\)/);
});

for (const [file, branch] of [['release-rc.yml', 'next'], ['release.yml', 'master']]) {
  test(`${file} binds JReleaser to the validated release branch`, () => {
    const workflow = fs.readFileSync(path.join(__dirname, '../workflows', file), 'utf8');
    assert.match(workflow, new RegExp(`JRELEASER_BRANCH: ${branch}\\b`));
  });
}

test('automatic RC reservation commits skip work without waiting behind a release', () => {
  const workflow = fs.readFileSync(path.join(__dirname, '../workflows/release-rc.yml'), 'utf8');
  assert.match(workflow, /group: "release-rc-\$\{\{ github.ref \}\}-\$\{\{ github.event_name == 'push' && startsWith\(github.event.head_commit.message, 'chore: release '\) && github.run_id \|\| 'active' \}\}"/);
  assert.match(workflow, /prepare:\n    if: "github.event_name == 'workflow_dispatch' \|\| !startsWith\(github.event.head_commit.message, 'chore: release '\)"/);
});
