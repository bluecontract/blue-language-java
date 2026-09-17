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
    const finish = workflow.indexOf('./gradlew jreleaserFullRelease --exclude-deployer=mavenCentral');
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
