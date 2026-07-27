## Unreleased (next 3.1.0 release candidate)

### Feat

- bind the corrected 125-fixture Language 1.0 and 127-fixture Contracts 1.0
  conformance packages and add a strict machine-readable release gate
- add the generic cyclic-set member mutation guard before provider demand
- split canonical identity construction from author-facing minimization
- add immutable `FrozenJsonPatch` APIs for direct frozen patch-value handoff
- add configurable per-runtime cache policy, cache statistics, and idempotent runtime close
- add explicit low-memory, high-throughput, and disabled cache policy profiles
- add production-path processing metrics snapshots and conservative patch-impact classification
- add immutable same-scope External Channel member context, including a shallow
  effective-type-family view whose exact dependencies participate in
  subscription invalidation, checkpoint domains, and sparse evidence verification
- add event-scoped External Channel pattern matching with inline/reference
  parity through a pass-local verified snapshot-manager boundary
- admit exact pure-reference Root and Event inputs through verified
  demand-driven fragments without recursively expanding the full graph
- add immutable same-scope handler routing and logical-delivery coalescing
  while raw accepted sources retain atomic checkpoint ownership
- add an event-scoped exact-reference materializer and representation-blind
  default projection for referenced subscription-key fragments
- preserve exact inline checkpoint subjects and expose both the frozen current
  subject and exact prior subject to channel newness policies

### Performance

- stream supported strict frozen canonical inputs directly into BlueId digests while retaining
  the generic JCS fallback and compatibility oracle
- reuse resolved metadata for dependency-proven basic scalar typed-leaf replacements
- bound reloadable derived snapshots, aliases, recent-processing state, shared verified-reference
  acceleration, and structural interning while preserving authoritative and active pinned evidence
- make conservative per-runtime cache bounds the default profile and keep the previous larger
  bounds behind an explicit high-throughput profile
- replace hot implicit decimal/index regex compilation with exact ASCII scans

### Compatibility

- remove deprecated pre-1.0 aliases, routed-delivery carriers, trusted provider
  behavior, ambiguous reverse APIs, and fatal-termination compatibility paths
- retain the released `NodeProviderWrapper.unverified(...)` and
  `isExplicitlyHostTrusted(...)` descriptors for `blue-repo-java:3.0.0-rc.10`
  linkage while enforcing verification and always denying host trust
- keep raw accepted sources as checkpoint owners while allowing immutable
  same-scope handler selection and logical-delivery coalescing
- record that downstream BEX 1.1 still needs a named live counter stream before
  it can supply conforming runtime child-ledger traces
- retain Java 8 bytecode targeting
- preserve full-resolution fallbacks for schema, fixed-value type, reference, collection,
  contracts-changing, custom-merger, and unknown-capability cases
- add reproducible source-release archives and JVM descriptor compatibility reporting
- pin the Gradle wrapper distribution checksum
- honor `SOURCE_DATE_EPOCH` for reproducible build metadata timestamps

### Fix

- pass all corrected Contracts fixtures without an expected-failure whitelist
- preserve whole-invocation rollback and zero provider demand when rejecting
  traversal below a cyclic-set member reference
- keep nested transient planning scopes from closing parent reference state
- serialize shared processor-registry and type-resolution updates against processing and reject
  lock upgrades instead of deadlocking
- defensively own mutable raw map, list, and array payloads while preserving Jackson/JCS enum
  and array behavior
- drain work admitted through `Blue` runtime APIs during close and reject new or re-entrant
  cache-sensitive work
- isolate retained conformance views from refreshed cache generations
- bound transient trusted reference retention and report its real eviction/rejection counters
- merge an admitted named runtime child ledger before rollbackable handler
  effects so its gas and ordered trace survive a later runtime-fatal rollback

## v2.0.0 (2026-05-13)

### Feat

- add partial result support to `ProcessorFatalException`

## v1.0.0 (2026-05-12)

### Feat

- stabilize Blue language Java 1.0

### Fix

- restore Java 8 CI compatibility

## v0.8.0 (2025-10-24)

### Feat

- use core type blue ids from blue-repository (#11)

### Fix

- change list Blue ID in Preprocessor and Properties classes (#13)
- **java8**: restore JDK 8 compatibility by removing Java 9–11 APIs (#12)
- **types,merge**: treat List/Dictionary as subtypes and add idempotent resolve test (#10)

## v0.7.3 (2024-10-09)

### Fix

- BlueId calculation for nested structures (#7)

## v0.7.2 (2024-10-07)

### Fix

- blueId calculation, reversing

## v0.7.1 (2024-09-25)

### Fix

- caching
- caching

## v0.7.0 (2024-09-23)

### Feat

- MergerReverser

## v0.6.1 (2024-09-17)

### Fix

- Double

## v0.6.0 (2024-09-13)

### Feat

- null mapping, updated limits
- object mapping

### Fix

- explicit mappings
- object mapping

## v0.5.0 (2024-08-08)

### Feat

- List and Dictionary support, Preprocessor
- ClasspathBasedNodeProvider

### Fix

- Disable core node provider
- Minor changes
- Java8 comp

## v0.4.0 (2024-07-23)

### Feat

- support for self-referring nodes

### Fix

- DirectoryBasedNodeProvider didn't process plain-text files properly

## v0.3.0 (2024-07-19)

### Feat

- CICD updates (#5)
- minor change to Blue

## v0.2.0 (2024-07-17)

### Feat

- Gradle build - Maven Central

## v0.1.0 (2024-07-17)

### Feat

- upgrade org.jreleaser

### Fix

- Typo in TestUtils

## v0.0.1 (2024-07-17)

### Feat

- gradle build - Maven Central
- prepare for maven publish

### Fix

- Sample serialization test
