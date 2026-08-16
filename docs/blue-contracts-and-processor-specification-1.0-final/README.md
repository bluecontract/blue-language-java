# Blue Contracts and Processor Specification 1.0 — Final Package

This package contains the final normative **Blue Contracts and Processor
Specification 1.0**, its unchanged core runtime registry, frozen gas manifest,
ordinary conformance corpus, affected-closure and cyclic-processing fixtures,
independent exact cyclic-identity oracles, implementation prompts, and
compilable Java reference templates.

There is no earlier published Contracts 1.0 and this package deliberately uses
version **1.0**, not 1.1.

## Normative release surface

```text
specifications/blue-contracts-and-processor-specification-1.0.md
conformance/contracts/registry/
conformance/contracts/gas-manifest.yaml
conformance/contracts/identity-constructors.yaml
conformance/contracts/fixtures/
conformance/contracts/oracles/
conformance/contracts/release-manifest.yaml
```

The normative corpus contains:

```text
133 normative vectors
154 ordinary executable fixtures
33 affected-closure/cyclic normative vectors
35 full affected-closure/cyclic scenario fixture files
18 exact named-limit boundary microfixtures
53 closure fixture files
207 Contracts fixture files in total
41 cyclic-identity oracle files
118 independently recomputed cyclic stages
```

## Informative architecture and integration material

```text
examples/CYCLIC_PROCESSING_EXAMPLES.md
SPEC_FINALIZATION_NOTES.md
IMPLEMENTATION_INTEGRATION_MAP.md
MANDATE_LAYER_COMPATIBILITY.md
prompts/PROMPT_00_FINAL_SPEC_AND_FIXTURE_AUDIT.md
prompts/PROMPT_01_BLUE_LANGUAGE_JAVA.md
prompts/PROMPT_02_BLUE_CONTRACT_JAVA.md
prompts/PROMPT_03_CROSS_REPOSITORY_INTEGRATION_AND_RELEASE.md
prompts/PROMPT_04_IMPLEMENTATION_SEQUENCE.md
```

## Java reference templates

```text
java-templates/reference/src/main/java/
    36 Contracts/closure reference classes

java-templates/coordination/src/main/java/
    15 Coordination integration reference classes
```

All 51 Java sources compile together with:

```text
javac --release 8 -Xlint:all,-options -Werror
```

The `-options` exclusion suppresses only newer-JDK warnings that Java 8 is an
old target; all source and bytecode still use `--release 8`, and every other
lint warning remains fatal.

The reference mains establish the scheduling, identity, gas, rollback,
historical-revision, closure-planning, and atomic-publication shapes expected
by the prompts. They are not substitutes for real implementation conformance.

## Explicit exclusions

- no BEX specification, BEX fixture corpus, or BEX implementation;
- no Coordination profile document;
- no Timeline-provider completeness or Coordination scheduling policy;
- no delegated-authority or Mandate eligibility semantics in the normative
  Language/Contracts package;
- no database, queue, network, or storage implementation;
- no modified existing core runtime type node or BlueId.

`MANDATE_LAYER_COMPATIBILITY.md` is informative only. It explains why the
generic Language and Contracts mechanisms are sufficient for a later
Mandate-aware Coordination feeder without adding Mandate semantics to this
release.

## Gas and limits

A document-authored gas policy is optional. This Contracts release binds one
exact finite default and one exact gas manifest. A host or local document/member
policy may lower the effective allowance, but it may not silently raise the
release maximum or create an independent meter for an embedded member.

All work in one affected closure shares the same deterministic meter. Therefore
an embedded-member local limit or the shared release limit can fail and roll
back the complete required closure.

The cyclic canonical-byte safety limit uses a representation-normalized
accounting form, so semantically equal inline and exact-reference children have
the same count. Gas fixtures also bind exact rejected-charge evidence, immediate
cyclic-finalization interleaving, and incremental acyclic containing-spine
identity work without double charging established exact values.

## Validation levels

```text
PACKAGE_VALID
    manifests, hashes, schemas, exact identities, active graph edges,
    Channels, Handlers, ordering keys, statuses, diagnostics, gas traces,
    limits, and static fixture laws pass

SEMANTIC_REFERENCE_VALID
    independent cyclic identity, dynamic cycle, finite reaction, gas loop,
    A5-to-A10 revision evidence, limit, and Coordination integration reference
    checks pass

IMPLEMENTATION_CONFORMANT
    the exact blue-contracts-core artifact executes every required fixture
    under the bound release and gas identities
```

The first two levels are reproduced with:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/validate_package.py \
  --source /path/to/the/original/spec-source.zip \
  --write-output
```

The source filename is non-normative; the validator checks its SHA-256 content
identity.

## Authoritative regeneration

The final closure corpus is produced by two phases: the seed generator and the
normative refinement pass. Do not run `generate_closure_fixtures.py` alone as a
release build; its 30-fixture intermediate output is intentionally incomplete.

Use the staged package command for every regeneration. It requires both the
exact Language/Contracts source tree whose baseline hashes are bound into the
release manifest and the original specification source ZIP:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/regenerate_package.py \
  --language-source-root /path/to/blue-language-java \
  --source-zip /path/to/blue-contracts-and-processor-specification-1.0-final.zip \
  --check
```

`--check` is read-only: it copies the package to a temporary directory, runs
seed generation, refinement, manifest construction, and complete validation,
then requires byte-identical output. The staging build first clears its copied
closure-fixture, trace, and cyclic-oracle YAML surfaces, so retired generated
files cannot survive just because an earlier manifest listed them. After
intentional generated changes, use `--write`; it performs the same temporary
build and validation before publishing the staged files with atomic per-file
replacement.

Implementation conformance is intentionally not claimed by this archive. The
Codex prompts require the real `blue-language-java` and
canonical `blue-coordination-java` project located at `../blue-contract-java`
to execute the complete corpus before a release artifact may claim conformance.
