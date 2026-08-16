# Affected-Closure and Cyclic Processing Fixtures

These fixtures are normative for the Blue Contracts and Processor Specification
1.0 affected-closure path.

They complement, rather than replace, the ordinary one-Root fixtures in the
sibling directories.

## Operations

```text
process-closure
    Process one original exact external event against one frozen affected
    closure. The closure may begin acyclic and may form, merge or split cyclic
    components during the invocation. This operation exposes the closed
    Complete-or-NeedsResources attempt result; NeedsResources commits neither
    semantic state nor gas.

admit-closure
    Initialize/admit one frozen affected closure without fabricating a Timeline
    Entry or provider timestamp.

limit-micro
    Independently exercise one named portable-limit guard at its exact bound or
    first-above value. It does not claim the enclosing closure can also finish
    before another independently applicable guard or the shared gas ceiling.
```

Limit-micro validation expands or streams the generator declared in each
fixture and measures the constructed semantic input. `input.observed` is an
expected result, never the source of the measurement. In particular, the
canonical-byte generator materializes its exact padded two-member ring and runs
the unchanged cyclic-set canonical-limit calculation over that value.

## Exact fixture requirements

Every active edge is present in the exact source document and declared through
its effective `Process Embedded` contract. Stable occurrence identity identifies
the continuing source/path/target activation and excludes the current target
BlueId. State-specific binding identity includes that target BlueId and changes
with it. Neither creates another authored graph.

Every full fixture exposes recomputable direct-delivery snapshot,
occurrence-binding set, closure, invocation, managed-scope, source-occurrence,
and work-occurrence identities using the normative constructor registry.

Every direct delivery names an actual `ScriptedExternalChannel`. Every runtime
Handler name resolves to an actual `ScriptedHandler` and its bound Channel.

Cyclic identities and proofs are not placeholders. The files in
`../../oracles/` contain independently generated current and expected cyclic
set constants. The package validator recalculates them with the unchanged Blue
Language cyclic-set algorithm.

The authoritative package regeneration entry point is
`../../../../tools/regenerate_package.py`. The seed generator is not a
standalone release command; see the package root README for read-only `--check`
and staged `--write` usage.

## Flagship fixtures

```text
c-clo-02-dynamic-finite-cycle.yaml
    Initial B -> A. A's external operation adds A -> B and emits X. A handles
    X locally, B handles X and emits Y, and A handles Y. The complete component
    is tentatively finalized after every identity-affecting step.

c-clo-03-exact-tentative-identity-visibility.yaml
    Proves that B and A read exact current temporary MASTER#index values, not
    application-visible virtual handles.

c-clo-04-default-policy-loop.yaml
    No authored/host gas policy. The frozen release default deterministically
    stops the same-event A/B loop.

c-clo-22-a10-attach-a5-needs-resources.yaml
c-clo-23-a10-attach-a5-catch-up.yaml
    A is authoritative at epoch 10 while B supplies A epoch 5. The first case
    lacks exact revision evidence and commits nothing; the second applies the
    exact contiguous A5->A10 chain without downgrading or forking A.

c-clo-33-checkpoint-domain-retirement.yaml
    A retired source checkpoint domain is treated as virtual empty, replaced
    after successful quiescence, and an orphan raw-key entry is removed in the
    same exact settlement batch.
```

## Validation levels

```text
PACKAGE_VALID
    YAML, schemas, identities, active-edge/document consistency, Channel and
    Handler references, manifests and Java reference templates are valid.

SEMANTIC_REFERENCE_VALID
    Independent exact identity, finite-cycle, gas-loop, history and limit
    reference checks pass, including semantic admission-candidate gas,
    invocation-owned event dequeue, checkpoint replacement/removal settlement,
    and incremental acyclic containing-spine identity work.

IMPLEMENTATION_CONFORMANT
    The actual blue-contracts-core conformance runner executes every fixture and
    matches all exact results. This package does not claim that level by itself.
```
