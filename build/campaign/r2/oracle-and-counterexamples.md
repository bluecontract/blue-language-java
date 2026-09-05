# Independent assurance and minimized regressions

Seed: `0xD2E6A11L` (221145617). The executable authority is `FiniteConstraintOracleTest`; assertions include the seed/sample on failure.

The enum oracle's expected result uses ten parent-index domains and immutable domain/payload/exact-reference records. It does not call production membership or intersection to compute expectations. Twenty scalar candidates and 28 restrictions cover Text, Integer, Double, Boolean, custom descendants, a sibling, a two-level custom chain and a Text-derived type named Integer. All 784 singleton pairs check intersection, commutativity and membership conjunction. Fifty-eight deterministic adjacent union triples check exact normalization, idempotence, associativity, commutativity, unsigned UTF-8 canonical ordering and unchanged inputs. Bare core entries are mixed with equivalent explicitly typed duplicates. Exact custom scalar identity remains different from the primitive scalar identity.

The numeric oracle independently extracts IEEE-754 sign/exponent/significand bits into BigInteger rational pairs. It checks comparisons, divisibility and normalized rational LCMs across zero, signs, safe/large Integers, 1e23, .1/.3, minimum subnormal, twice minimum subnormal, 1e-300 and maximum finite Double. Independently computed LCM numerators and twice those numerators are accepted witnesses; this prevents a merely stronger common multiple from passing sparse membership samples.

Focused minimized regressions:

1. Double 1e23 equals Integer 99999999999999991611392, not decimal Integer 100000000000000000000000. Validation, matching and bound propagation compare exact numeric values.
2. Double 9007199254740992 is not a multiple of Integer 9007199254740993.
3. LCM(binary64 .1, binary64 .3) = 19471113219505603967277331424215 / 18014398509481984. Double .3 fails; the numerator, twice the numerator and zero succeed. Unrepresentable dyadic LCMs normalize to their Integer numerator over the supported value domain.
4. minimum 5 + exclusiveMaximum 5, and exclusiveMinimum 5 + maximum 5, are rejected without payload.
5. A lone Text numeric bound on an absent typed candidate is malformed, even though a valid absent-payload predicate defers.
6. An outer List type whose itemType references Text value bad with enum [good] must fail cold and warm. Cache identity proof cannot suppress this fixed-value validation.
7. A typed fixed reference under zero budget is INCOMPLETE, including when the budget exception is wrapped by reference materialization.
8. Opaque provider invalid-evidence diagnostic text still produces INVALID/INVALID_EVIDENCE.
9. Wire-equivalent schema keyword nodes with absent versus explicit core types remain distinct structural cache entries. Exact repeated representations still share.
10. A 96-entry custom enum intersection made 9,217 provider reads under a one-ID budget before the fix; invocation-local evidence reuse reduces that to one. A separate 48-layer ancestry test enforces an eight-ID cutoff, valid warm completion, invalid empty fixed payload rejection and independence of later zero-budget calls.

Missing evidence and later provision, invalid evidence, quoted numeric strings and custom numeric primitive projection are additionally exercised by `EnumConstraintMembershipTest`, `DefinitionSdkHandoffTest`, `DefinitionLocalityTest`, and existing BasicScalarPayloadKind tests. No network source or host-specific authority is used.

11. Simplified schema serialization previously erased explicit large Integer keyword and custom enum types. Its output could not be parsed back as the same constraint. Explicit schema entries now use official node representation even inside simplified output; ordinary legal scalar sugar remains unchanged.
