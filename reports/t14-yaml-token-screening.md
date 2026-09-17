# T14: portable YAML token screening

## Assessment and plan made before implementation

Low-to-moderate implementation complexity, with low expected performance risk.
The default codec already parses SnakeYAML events to validate object keys before
Jackson builds the JSON/Blue representation. Extend that existing pass to inspect
anchors, aliases, explicit tags, and plain merge keys, and remove the raw-text
regex guards. This keeps the same parser pass count, linear input traversal, and
depth-proportional validator stack. No dependency upgrade or YAML preprocessing
is needed. Measure parsing including Node construction on Java 8 before/after.

Plan: reproduce at `StandardBlueCodec`, retain the failing baseline, implement the
token checks, check literal text and identity preservation, run focused and full
root/core tests, and compare a repeatable JMH benchmark with a JSON control.

## Verified scope and change

Confirmed on baseline `979916fe9c450cbe6344fa1a20a66e382b72f551`
(`3.1.0-rc.27-SNAPSHOT`), newer than the audit's `91395481`.
The current Language specification SHA-256 is
`77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144`;
section 2.2 still requires portable rejection of anchors, aliases, and merge keys.
The supported profile is the default strict `StandardBlueCodec` YAML source and
BlueId-input boundary, including the existing guarded mapper overloads. There is
no nonportable alias-expansion option at this boundary.

Minimal reproduction before the fix:

```java
StandardBlueCodec codec = new StandardBlueCodec();
codec.parseSource("'hello !literal &literal'", BlueFormat.YAML); // wrongly rejected
codec.parseSource("hello # !literal &literal", BlueFormat.YAML); // wrongly rejected
codec.parseSource("[&x 1,*x]", BlueFormat.YAML);                  // wrongly accepted
codec.parseSource("<<: {a: 1}", BlueFormat.YAML);                // wrongly accepted
codec.parseSource("'hello'", BlueFormat.YAML);                  // valid control
```

The initial regression had 3 failures in 4 tests. The expanded raw-input matrix
had 36 failures in 73 tests before any production change.

- `YamlObjectKeyValidator` now checks node-event anchor metadata (including alias
  events), scalar/collection tag metadata, and plain `<<` tokens in key position.
  It does not compose YAML objects or expand aliases.
- `UncheckedObjectMapper` delegates to that existing pass without scanning source
  text with tag/anchor regexes.
- `BlueYamlStringQuotingChecker` quotes literal `<<` property names so the writer
  does not create forbidden merge syntax when round-tripping ordinary string keys.
- `IndependentT14RegressionTest` keeps raw YAML spellings directly in the root
  acceptance suite. It covers flow/block syntax, undefined and recursive aliases,
  Unicode anchors, scalar/collection/key tags, merge-key context, escaping,
  comments, folding/chomping/CRLF, duplicate/non-string keys, JSON controls, and
  the guarded string/stream/generic mapper entry points.
- `YamlTokenScreeningBenchmark` exercises five-field input, 1,000-field input,
  and long text through the actual codec for both YAML and JSON.

For accepted text, tests compare the exact parsed String and its BlueId against
an explicitly constructed Text node. Different block-scalar newline results keep
different identities. Rejected inputs fail before Jackson constructs a Blue Node;
no provider, processor, gas accounting, events, subscriptions, checkpoints, or
publication participates in this boundary. This is not a downstream host rollback
or exactly-once processing claim.

The tag ban remains the existing strict codec policy, including explicit built-in
tags. The scalar resolver is unchanged; the controls cover quoted YAML-1.1-like
text, string keys, and timestamp text, not a broader resolution of T07.

## Compatibility and limits

ID-06 and its duplicate ID-09 are addressed by the same event-level guard.
Previously rejected literal punctuation now parses. Previously admitted anchors,
aliases, and plain merge keys now fail. Existing callers using a string property
named `<<` must quote it in YAML; the codec writer does this automatically.
The JSON representation and Blue identity algorithm are unchanged.

CONF-06's *published conformance fixture format* remains a separate package change:
its parsing operations serialize structured fixture fields and cannot retain all
raw YAML spellings. This patch preserves those spellings in acceptance tests; it
does not claim that the published fixture package now supports them. T14 requires
a separately reviewed migration manifest for package identity changes, so no
fixture schema, fixture inventory, release manifest, or identity was regenerated.
No release-readiness or full portable-conformance claim follows from these checks.

## Evidence

The isolated worktree is `/private/tmp/blue-t14-yaml-token-screening-20260916`,
branch `fix/t14-yaml-token-screening`. Raw local evidence is retained in
`/private/tmp/blue-t14-evidence`, including baseline failure XML, focused/full test
logs, parser dependency hashes, source hashes, and JMH logs/JSON.

Toolchain: Gradle 9.6.0, running on Temurin 25.0.4.1; tests and JMH use Zulu
OpenJDK 8u504 (`1.8.0_504-b01`, macOS aarch64), not the audit's Java 25 test override.
Jackson remains 2.15.2 and SnakeYAML remains 2.0. Their exact unchanged JAR hashes
are recorded in `parser-dependency-hashes.json` in the evidence directory.

The full root suite passed **2,872 tests in 279 classes**; Language Core passed
**173 tests in 34 classes**. There were no failures, errors, or skipped tests.
The combined Gradle run completed in 5 minutes 3 seconds. Its log is
`full-suites.log`; retained XML is under `full-root-xml/` and `full-core-xml/`, with
counts in `full-test-counts.json`. `git diff --check` also passed.

Executed from the isolated worktree, with
`JAVA_HOME_8_ARM64=/private/tmp/issue22-jdk8/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home`:

```sh
bash ./gradlew :test --tests blue.language.identity.IndependentT14RegressionTest --no-daemon --max-workers=4
bash ./gradlew :blue-language-core:jmhJar --no-daemon --max-workers=4
bash ./gradlew :test --tests blue.language.identity.IndependentT14RegressionTest --tests blue.language.SourceDocumentBlueIdTest --tests blue.language.SourceStyleConventionsTest --tests blue.language.NodeDeserializerTest :blue-language-core:jmhJar --no-daemon --max-workers=4
bash ./gradlew :test :blue-language-core:test :blue-language-core:jmhJar --no-daemon --max-workers=4
```

The first command was run against the four-case baseline and then the expanded
73-case baseline. Their logs are `baseline.log` and `baseline-expanded.log`;
failure XML is under `baseline-xml/`. The focused command's successful result is
`focused-fixed.log` and `focused-xml/`: 73 T14 + 54 NodeDeserializer + 13
SourceDocumentBlueId + 10 SourceStyleConventions tests, all 150 passing.

The benchmark JAR is built once before production changes and again after them.
Both use the same added benchmark class and unchanged dependencies. JMH 1.37 is
run with the Java 8 executable above, one thread, two forks, three 500 ms warmup
iterations, five 500 ms measurement iterations per fork, a fixed 512 MiB heap,
average time in microseconds, and the GC allocation profiler:

```sh
java -jar /private/tmp/blue-t14-evidence/baseline-jmh.jar YamlTokenScreeningBenchmark -wi 3 -i 5 -w 500ms -r 500ms -f 2 -t 1 -bm avgt -tu us -prof gc -jvmArgs '-Xms512m -Xmx512m' -rf json -rff /private/tmp/blue-t14-evidence/baseline-final-jmh.json
```

For the fixed measurement, replace `baseline` with `fixed` in the JAR and output
paths. The baseline was repeated after the test suites to remove overlap with
build/test work from the comparison. This is a local warmed microbenchmark of
accepted documents, not a production workload or malicious-input cost bound.

Results (mean microseconds per parse, before → after; lower is better):

- YAML, five fields: **50.38 → 34.01**, 32.5% lower time.
- YAML, 1,000 fields: **8,531.05 → 5,336.75**, 37.4% lower time.
- YAML, long text: **2,445.61 → 385.40**, 84.2% lower time.
- JSON, five fields: **4.70 → 4.90**, 4.3% higher time (0.20 µs).
- JSON, 1,000 fields: **961.53 → 954.19**, 0.8% lower time.
- JSON, long text: **61.29 → 60.61**, 1.1% lower time.

No large negative performance impact appeared in these samples. YAML allocation
per parse also decreased: approximately 198,352 → 194,136 bytes (small),
28,957,754 → 28,687,290 bytes (wide), and 2,652,640 → 2,650,706 bytes (long text).
JSON allocations were unchanged to rounding. Do not interpret the short two-fork
run as a precise production speedup guarantee. Per-iteration data, confidence
intervals, allocation data, and the small JSON timing increase are retained in
`baseline-final-jmh.json`, `fixed-final-jmh.json`, and `performance-comparison.json`.

The benchmark JAR entry comparison showed changes only in the three edited
production classes and their inner classes; dependency, resource, and benchmark
class bytes were identical. The artifact SHA-256 values are:

```text
baseline-jmh.jar b69ca8916d5a97ddd28bedab48496853dc264e8c8db1a3204ddaeb7ba5625377
fixed-jmh.jar    7f97b053433d76310f1e166d4adc395f37e508cf96274f8ef0ae7ab34a5f8564
```

Unchanged parser dependency JAR SHA-256 values (same before and after):

```text
jackson-annotations:2.15.2     04e21f94dcfee4b078fa5a5f53047b785aaba69d19de392f616e7a7fe5d3882f
jackson-core:2.15.2            303c99e82b1faa91a0bae5d8fbeb56f7e2adf9b526a900dd723bf140d62bd4b4
jackson-databind:2.15.2        0eb2fdad6e40ab8832a78c9b22f58196dd970594e8d3d5a26ead87847c4f3a96
jackson-dataformat-yaml:2.15.2 37795cc1e8cb94b18d860dc3abd2e593617ce402149ae45aa89ed8bfb881c851
snakeyaml:2.0                 880c9d896e4b74a06c549c15ca496450165d6909fa15d7e662bee8f6a66d7afa
```

The fixed revision is the commit containing this report on
`fix/t14-yaml-token-screening`; its exact Git SHA is also retained in
`/private/tmp/blue-t14-evidence/fixed-commit.txt`. No dependency, specification,
fixture-package, or identity manifest change accompanies the implementation.
