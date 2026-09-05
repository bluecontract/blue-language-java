# E2 profile and B2 infrastructure handoff

Workload: eight managed lineages in one finite ring, initial countdown 17,
eighteen reactions, eight transition receipts. Three warmup invocations and
twelve measured invocations; every invocation independently constructed its
fixture. Timing covers closure execution, not fixture construction. The
process-wide JFR samples also include setup and warmup. This is a representative
local profile, not a cross-machine performance threshold or release benchmark.

Command (run from the Language worktree):

```sh
./gradlew :blue-contracts-core:profileR2 --init-script build/campaign/r2/profile.gradle --offline --console=plain
```

The profile uses JDK 26.0.1 with a 768 MB heap and JFR `profile` settings; normal
correctness tests use the configured Java 8 toolchain. Recording path is
`/private/tmp/r2-composition.jfr`. Change that path in a copied init script if
needed. Raw recording is local and intentionally not committed.

Observed measured runs:

- median: 377.183 ms; minimum: 363.351 ms; maximum: 386.652 ms;
- logical gas: exactly 18,074 in every warmup and measured invocation;
- JFR: 413 execution samples, 1,935 allocation samples, 136 GC events;
- sampled leaf frames: CanonicalJsonValueWriter.writeString 41,
  regex Pattern union 38, BigInteger.add 31;
- nearest Blue frames: BlueIds.hasCanonicalSha256DecodedLength 44,
  CanonicalJsonValueWriter.writeString 41, NodeGraphCopier.copyInto 15,
  BlueIdInputNormalizer.cleanMap 13, CanonicalJsonValueWriter.writeMap 13.

Allocation sample weights were highest for byte arrays (~3.63 GB), int arrays
(~2.36 GB), BigInteger (~1.16 GB), and hash-table arrays (~0.96 GB). These are
sampling estimates over the entire process, not live heap sizes or exact bytes
per invocation. No optimization was applied; warmup did not alter logical work.

B2: investigate shared canonical identity/BlueId validation, regex/pointer
handling, and clone/allocation costs before introducing Contracts-specific
caches. Any optimization must retain provider verification, exact Source
ownership, work ordering, output identities, and gas quantities. The small
sample cannot establish causality or a regression relative to the base.

Supplemental observation: the combined full module/gate/exact-conformance
command completed in 4m 29s. During Contracts closure conformance the Java 8
process had about 6.1 GB RSS and used roughly one CPU; a stack sample showed
active resolution/canonicalization, not a deadlock. The release task emits no
per-fixture progress and sets no explicit heap ceiling. B2 may consider bounded
heap configuration and fixture progress reporting. No build infrastructure was
changed in this lane.

Exact source commits and validation provenance are in `00-handoff.md`. Package
and release identities were not regenerated or sealed by the profiling run.
