# Witness-context release qualification inputs

## Problem

The immutable-witness runtime correction and supported package generation are
described in [the source correction note](rooted-immutable-witness-context.md).
Clean `430ee3936af1e7d73bc82032484e31c83851cec3` imports that generated manifest.
It does not change any of the other 382 package files.

Two separate tooling checks correctly require attention before final gates:

- The historical legal-detached transition test read the active package as
  its old after-image. Parent recorded four tests, three PASS and one FAIL:
  the expected historical manifest SHA `f26af7b55e0f4aa9491b32bed2cd6b3c2a0c10e34e749ba4263845cbd2f90d0b`
  differs from `430ee`'s `85af8e6913447831b6791eafd3f81b3bf5701b52ef1d11b6cc5b17587f38482a`.
  Python unittest execution took 150.909 seconds; Gradle finished red in
  2 minutes 35 seconds.
- Correct-root strict classification of `3491` against the generated
  `release/conformance/contracts` package exited 2. The physical change is one
  manifest; the classifier also emitted its synthetic release-integrity
  failure because this new exact pair was not registered. Thirteen older C-EVO
  fixture hashes and the current manifest were not recognized in that new pair.
  This is not evidence that those fixture bytes changed.

The earlier classification accidentally targeted the whole generated release
shell and reported 1,523 unexpected changes. It remains launcher-error evidence,
not the package delta or a runtime failure. Parent retains all three original
logs/reports under external `legal-detached-retarget-evidence.fKnxrU`.

## Bounded correction

The new historical archive is copied from the clean, committed `3491` resource
package. All 383 names and hashes match the existing historical after-inventory
exactly, including its original manifest. Its checksum is checked before safe
temporary extraction. The historical test's four positive/negative methods
and old digest-pinned review record remain unchanged; only its after-image
loading changes. No historical approval follows the active checkout anymore.

The new witness-context record is a separate exact metadata transition:

- Before: clean `3491`, release
  `sha256:56e69e4260d87261aafc5158a0c68815bb5451b6c33cf100795a820eccd490da`.
- After: clean `430ee`, supported generation input `64f368`, release
  `sha256:14a9653062c2b4d456c54313db55d22fe92573bd5bcf4f3e87b50400918116f1`.
- Both inventories have 383 files and the same 741 implementation paths. Only
  eight implementation digests and the derived release identity change.
- All other 382 files and all 295 executable fixtures are byte-identical. There
  is no specification, outcome, gas, trace, event, receipt or ordering exception.

The classifier hashes the complete immutable review record, then requires both
full package inventories. Existing release integrity checks still run. The new
tests reconstruct the after-image with the complete **actual generated** manifest
bytes retained in that record, not guessed replacements in a future package.
They check manifest identities and exactly eight source changes; mutated status,
gas, order, inventory and rehashed source/review inputs remain rejected. A new
maintained Gradle Exec task wires these controls into both module `check` and
release conformance. No threshold, public API, Java production source, current
package resource, old baseline or release credential changes.

## Evidence boundary and next gate

This source/test/tooling slice is prepared for independent review. It has not
been executed after the correction. Parent owns the serialized gate:

```bash
./gradlew --no-daemon --no-build-cache --max-workers=1 --no-parallel \
  :blue-conformance:legalDetachedRetargetReleaseTransitionTest \
  :blue-conformance:witnessContextReleaseTransitionTest \
  -PbluePythonExecutable=/absolute/path/to/the/qualified/python3
```

Then rerun strict classification on the preserved `3491` package and the same
supported generated Contracts subtree with external JSON/Markdown outputs and
`--fail-on-unexpected`. Expected result after review is one changed manifest,
zero unexpected changes, the new exact transition ID and no semantic category.
No positive result is claimed until parent executes and archives it.

The full Contracts module's 578-test PASS and D03's exact test-only downstream
settlement remain evidence for their separately identified sources/tuples. This tooling correction
does not imply a full clean/quality pass, production diamond scheduling, MyOS
acceptance, performance success, release readiness or merge authorization.
Its new source commit changes DEVELOPMENT coordinates and must be rebound in
final exports even though the Contracts package/runtime identities are unchanged.
