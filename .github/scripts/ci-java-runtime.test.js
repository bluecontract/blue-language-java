const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../..');
test('production CI and conventions use Java17 while libraries keep Java8 bytecode', () => {
  const read = file => fs.readFileSync(path.join(root,file),'utf8');
  assert.match(read('.github/actions/verification-setup/action.yml'), /java-version: '17'/);
  for (const file of ['build-logic/build.gradle','smoke-tests/published/build.gradle',
        'build-logic/src/main/java/blue/buildlogic/ConformancePackagePlugin.java']) {
    assert.match(read(file), /JavaLanguageVersion.of\(17\)/);
    assert.doesNotMatch(read(file), /JavaLanguageVersion.of\(25\)/);
  }
  assert.match(read('build-logic/src/main/java/blue/buildlogic/Java8LibraryConventionsPlugin.java'), /JAVA_RUNTIME_VERSION = 17/);
  assert.match(read('build-logic/src/main/java/blue/buildlogic/RootOrchestrationPlugin.java'), /JAVA_RUNTIME_VERSION = 17/);
  assert.match(read('build-logic/src/main/java/blue/buildlogic/support/StagedRepositoryManifest.java'), /REQUIRED_BUILD_JAVA = 17/);
  assert.match(read('build-logic/src/main/java/blue/buildlogic/Java8LibraryConventionsPlugin.java'), /getRelease\(\).set\(BYTECODE_VERSION\)/);
});

test('library bytecode remains Java8', () => {
 assert.match(fs.readFileSync(path.join(root,'build-logic/src/main/java/blue/buildlogic/Java8LibraryConventionsPlugin.java'),'utf8'), /BYTECODE_VERSION = 8/);
});

test('semantic evidence launcher also uses Java17', () => {
 const source = fs.readFileSync(path.join(root, 'build-logic/src/main/java/blue/buildlogic/SemanticEvidenceOrchestration.java'), 'utf8');
 assert.match(source, /JAVA_VERSION = 17/);
 assert.match(source, /JavaLanguageVersion.of\(JAVA_VERSION\)/);
});
