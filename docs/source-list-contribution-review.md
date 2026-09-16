# Positional replacement Source-contribution diagnostic

Base: `101bcadff3c2638c3e43454f5d4aef0fbc07eece`. The correction changes only
the new working Source-contribution selector, not Language resolution,
minimization, processing rules, public API signatures or published bindings.

Language §11.2 requires `$replace` for a non-scalar positional replacement.
The initially considered bare `$pos: 0, items: [...]` form is therefore **not**
the acceptance case, even though a permissive implementation branch exists.

The valid case inherits a list slot whose custom type declares
`definition: {blueId: D}`. A later `$pos: 0, $replace: {marker: new}` replaces
the slot's ordinary fields but preserves its inherited type constraints.
`ListOverlayMerger.mergeOrReplacePosition` selects the inherited element type;
`resolveListChild` applies replacement to a fresh target with that type/schema.
The expected definition comes from the unchanged custom type, not from the
discarded old slot's ordinary fields.

The new provenance selector clears the old slot at `$replace`. The diagnostic
checks whether it also retains the required type contribution at a deeper path.
It first asserts the existing resolved path and absence of the replaced ordinary
field, then compares provenance with the independently retained exact definition
`D`. It does not infer the expected Source value from the API being tested.

Four controls share this scenario: inherited slot type with untyped replacement;
the same explicit replacement type; a list-level `itemType`; and an untyped slot
whose old ordinary definition must disappear.

## Reproduction and correction

The parent-owned run on `32fdd04af5a2be90c8798f06111d1f999b3fd8d8`
executed all four controls: **three passed and the inherited-slot-type case
failed**. Its existing resolver assertions passed first: the new marker and
type-supplied definition were present, and the obsolete ordinary field was
absent. Only the provenance assertion failed: it returned no contribution
instead of the independently retained definition `D`.

Evidence: `upstream-review-list-slot-01-result.json` and
`upstream-review-list-slot-01-build.tar.gz` in the qualification evidence root;
archive SHA-256
`928ce09b3f6f5abb2339c2ea807d90b17a0d3b0fb26dd44354e4c8a7c00e64ee`.

The selector now retains the inherited slot type in a separate private context
when replacement removes the old body. That context contributes only when the
requested path descends into the replacement; it is not returned as the exact
Source of the replacement itself. An explicitly selected replacement type
supplies its own ancestry instead, avoiding duplicate definition contributions.
References still pass through the existing invocation-bound verified lookup;
no merged Source value or synthetic identity is constructed.

This follows `ListOverlayMerger.mergeOrReplacePosition` and `resolveListChild`,
which retain type/schema constraints while replacing ordinary content. Schema
validation remains entirely with that existing resolver; the correction does
not turn schema constraints into additional authored fields. The untyped
control continues to require the discarded ordinary definition to stay absent.
The four tests and their original expected values are unchanged.

## Verified focused result

`upstream-review-list-slot-02` freshly executed the entire
`ReferenceTransparentExecutionTest` class on
`3474e82bb27f45716c5f8331bf1aab759be5f9ca`: **40 tests passed, zero failures,
zero errors and zero skips**. This includes all 36 preceding controls and the
four positional-replacement controls; it is not only a rerun of the failing
case. Recorded end-to-end run time was 11.697445 seconds.

Evidence: `upstream-review-list-slot-02-result.json` and
`upstream-review-list-slot-02-build.tar.gz`; archive SHA-256
`64fca7779e52d8b8c8f2745f8cccdebe19b359923ac7251c231ca307feadf990`.
This establishes the focused selector correction, not full library or MyOS
acceptance. Generated source-location inventories, the final source freeze,
and qualification of the final artifact tuple remain separate steps.

Parent-owned complete class command (40 invocations):

```sh
./gradlew :blue-contracts-core:test --rerun \
  --tests 'blue.language.processor.ReferenceTransparentExecutionTest' \
  --no-daemon --max-workers=1 --no-parallel --continue --console=plain
```

Duplicate `$pos` controls in one list are invalid under §11.6 and the maintained
validator. `$previous` is first-only and prefix-validated. Inherited positions
cannot be removed/reordered; Source null becomes a retained placeholder. These
rules make the minimizer's appended-tail index calculation well-defined; this
review has not demonstrated a separate minimizer defect.
