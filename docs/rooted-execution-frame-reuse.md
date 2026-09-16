# Decode-local reuse of verified nested execution frames

## Problem and example

This isolated POC successor starts at Language/Contracts
`d2f2ad00837bfed603b8706b54cd4e89b4aea262`, branch
`codex/poc-execution-frame-reuse`. It does not change the frozen resident baseline,
other engineers' branches, specifications, or processing rules.

An execution-storage attempt contains its original invocation snapshot and a
complete terminal result. `ClosureExecutionEvidenceStorageCodec` first decodes
each nested envelope through the existing snapshot/result codec. Those codecs
fully check and canonically re-encode their respective inputs. The enclosing
execution codec then canonically re-encodes the entire attempt, calling the
snapshot and result encoders again in newly opened storage calls. This repeats
pure reconstruction/verification work already completed for those exact nested
objects and bytes. The additional association checks in `validateAttempt` are a
different concern and are not removed.

The observed PostgreSQL ring/chord writer stack traverses this storage path.
That sample locates work; it does not measure this repetition's exclusive cost
or establish the unexecuted scenario suffix's correctness. The standalone
`ClosureProcessResultStorageCodec.decodeInCall` already keeps one snapshot scope
through its own final canonical comparison; that implementation is unchanged.

## Solution

The package-private `ExecutionStorageCall` is allocated for one public execution
decode. After a nested snapshot or complete result has successfully passed its
ordinary decoder, the call retains its exact Java object identity and the owned
nested byte array extracted from the outer envelope's private payload. Only the
outer canonical encoder can reuse that pair. Public encoders remain uncached.

- A failed, partially decoded, or encode-only object never enters the memo.
- An equal BlueId, closure identity, result identity, or byte array does not
  substitute for the retained object's identity at encoding time.
- Repeated equal envelopes still decode independently. This is **not** decoded
  object interning; witness sharing inside and between envelopes is unchanged.
- The final whole-envelope byte comparison, all constructor and result checks,
  and `validateAttempt`'s producer/input/binding/demand checks remain unchanged.
- The per-call payload budget is the configured maximum envelope size. Nested
  payloads are disjoint portions of the already bounded outer envelope; no
  additional payload copy is made for retention. A separate maximum of 4,096
  memo references limits bookkeeping. This is a physical cache bound, unrelated
  to any protocol closure limit. Oversized/excess entries fall back to encoding.
- Close clears all memo references and byte accounting on success or failure.
  There is no ThreadLocal, cross-call cache, provider access, host publication
  authority, external-cache dependency, or retained mutating runtime state.

The package-only counters measure outer nested-decoder calls, actual outer
nested-encoder calls, successful encode reuses, and retained/peak payload bytes
and references. They do not measure all validations inside the nested codecs or
the association checks. Payload bytes are not decoded heap size.

## Rationale and alternatives

The nested decoder has already established that its immutable restored value
encodes to those canonical bytes. Reusing that established representation when
checking the enclosing frame avoids a second derivation without weakening the
enclosing comparison. The bytes are privately read from the checked outer
payload, never shared with the caller, and not a public cache insertion API.

Interning equal decoded snapshots across separate envelopes was rejected because
it could change identity-based witness aliases. A global verification flag,
trust-based `skipValidation`, persistent decoded-object cache, larger deadlines,
and changes to gas/history/identity are outside this correction. Host-controlled
cross-operation L1 reuse is a separately designed follow-up, not implemented by
this short-lived memo.

## Tests and qualification

`ExecutionStorageCallTest` adds five controls:

1. Successful and G−1 result attempts: one snapshot and one result are still
   decoded, while enabled outer re-encoding calls both nested encoders zero
   times versus once each when disabled. Whole attempt/result bytes, gas traces,
   receipts, separate snapshot identities, defensive copies and no processing
   are compared.
2. Byte/entry/disabled bounds: normal encoding fallback preserves exact bytes.
3. Equal independent envelopes stay independent; encode-only values cannot
   acquire reuse authority.
4. Damaged and self-checksummed wrong-output result frames are repeatedly
   rejected before result memo admission, even after valid nested data was read.
5. Invalid bounds and closed scopes fail; close clears retained entries/bytes.

The existing complete execution, result, snapshot, and witness owners are also
required. No build, test, export, application rerun, speedup, or acceptance pass
is claimed by this source-editing agent. Parent-owned qualification is pending:

```sh
./gradlew :blue-contracts-core:test \
  --tests blue.language.processor.closure.ExecutionStorageCallTest \
  --tests blue.language.processor.closure.ClosureExecutionEvidenceStorageCodecTest \
  --tests blue.language.processor.closure.ClosureProcessResultStorageCodecTest \
  --tests blue.language.processor.closure.AffectedClosureSnapshotStorageCodecTest \
  --tests blue.language.processor.closure.RootedWitnessContextTest \
  --tests blue.language.processor.closure.RootedWitnessSelectionTest \
  :blue-contracts-core:javadoc \
  --offline --no-daemon --no-parallel --max-workers=1 --console=plain
```

Use the maintained pinned Java/dependency environment and retain complete native
results. The public API and storage formats are unchanged. Performance and MyOS
acceptance require a subsequently sealed candidate and unchanged application
oracles.
