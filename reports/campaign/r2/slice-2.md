# Slice 2: cache provenance and structured limits

Base: 6fb8ae00 (resolve via Git for full identity). Exact slice identity is the commit containing this report.

Three public SDK regressions were reproduced (06-locality-reproduction.log): a warm outer List metadata cache accepted an invalid fixed scalar; budget exhaustion while materializing a typed fixed reference became INVALID; opaque provider INVALID_EVIDENCE lost its structured outcome.

Corrections:
- Schema-bearing resolved reference cache entries are replayed from verified canonical content. Cache identity coverage never substitutes for current validation candidate registration.
- Limited resolution scans wrapped causes for its reference-budget exception and preserves typed INVALID_EVIDENCE independently of diagnostic wording.
- Frozen structural cache keys retain every modeled schema keyword/enum node and reference field. Wire sugar had collapsed distinct retained type metadata, making warm preparation lose exact resolver evidence. No identity lookup was broadened and no authored ID was trusted as materialization proof.

Verification:
- DefinitionLocalityTest: 4 tests, including a 48-layer finite ancestry, <=8 provider reads under an 8-expansion budget, cold/warm completion, and limited calls independent of warm caches (07/13 pass).
- SchemaStructuralProvenanceTest: 1 test, exact schema variants remain distinct while identical repeated variants share (14 pass).
- Expanded definition/Gender/partial matrix selection also passes (13); SDK fixtures pass (14). Full broader compatibility continues in the next slice.

Compatibility: schema metadata may replay merge work on warm access, but verified canonical caches still avoid repeat external reads. Structural sharing is retained for truly equal schema representations. No global cache or network authority added. No specification/binding identity changed by this slice itself.
