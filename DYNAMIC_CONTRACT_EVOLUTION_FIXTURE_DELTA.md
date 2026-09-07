# Full-lifecycle identity delta

- Before files: 383
- After files: 383
- Changed files: 84
- Unexpected files: 0
- Baseline commit: `03db5ee45f96698a45a3f5556dcc1ef6222d8e6f`
- Baseline tree: `69716bc726117a8587b16ac0757b1a5468b80355`
- Baseline fixture package: `sha256:3bb21b5df6eb87b578e9647f11d094aff2cf45c56b3b7050f8d854147bdb3e3d`
- Before reference: `git:42e407c914c7813f327a0ed62e7599bab06a0ecb:blue-conformance/src/main/resources/blue-contracts-closure-1.0`
- After reference: `worktree:codex/empty-objects-embedded-collections-bex-null-alignment:blue-conformance/src/main/resources/blue-contracts-closure-1.0`

## Classification summary

| Category | Changed files |
|---|---:|
| `spec-identity-rebind` | 83 |
| `invocation-identity-rebind` | 80 |
| `work-event-trace-identity-rebind` | 65 |
| `actual-semantic-state-result-change` | 2 |
| `fixture-byte-only-formatting` | 0 |
| `unexpected` | 0 |

## Changed files

- `fixtures/closure/c-clo-01-cyclic-materialized-parity.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-01-cyclic-pure-reference-parity.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-01-static-cycle-admission.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-02-acyclic-materialized-parity.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-02-acyclic-pure-reference-parity.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-02-dynamic-finite-cycle.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-03-exact-tentative-identity-visibility.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-04-default-policy-loop.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-04-same-event-gas-loop.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-05-direct-both-members.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-06-duplicate-event-occurrences.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-07-self-cycle.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-08-cycle-during-initialization.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-09-merge-two-cycles.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-10-split-to-singletons.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-11-split-into-two-cycles.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-12-frozen-edge-removal.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-13-frozen-edge-addition.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-14-invalid-cyclic-proof.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-15-ambiguous-preliminary-members.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-16-limit-at-bound.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-17-limit-above-bound.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-18-locality-1000-unrelated.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-19-multiple-public-roots-canonical.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-19-public-event-boundary.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-19-single-public-root.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-20-late-outer-failure.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-21-admission-zero-direct.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-22-a10-attach-a5-needs-resources.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-23-00-attach-a5-retry.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-23-01-a5-to-a6.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-23-02-a6-to-a7.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-23-03-a7-to-a8.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-23-04-a8-to-a9.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-23-05-a9-to-a10.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-24-occurrence-continuity.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-25-policy-identity-binding.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-26-embedded-local-cap.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-27-multiple-scc-one-closure.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-28-containing-spine-identity-gas.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-28-mixed-result-shape.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-29-active-edge-path-validation.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-30-language-cyclic-oracles.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-31-invocation-owned-gas-rejection.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-clo-32-finalization-owned-gas-rejection.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-33-checkpoint-domain-retirement.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-34-separate-document-steps.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-35-00-remove-and-create-successor.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-clo-35-01-readd-committed-successor.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-emb-empty-05-prospective-activation.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evo-18-missing-exact-node.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-19-missing-occurrence-evidence.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-20-canonical-demand-order.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-21-retry-determinism-missing-first.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-21-retry-determinism-missing-repeat.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-21-retry-determinism-resolved-first.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evo-21-retry-determinism-resolved-repeat.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evo-22-low-gas-expanded-evidence-demand.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-22-low-gas-expanded-evidence-expanded-low-gas-repeat.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evo-23-automatic-explicit-retry-parity-automatic-demand.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/c-evo-23-automatic-explicit-retry-parity-automatic-resolved.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evo-23-automatic-explicit-retry-parity-explicit-resolved.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evt-collection-07-closure-work-order-inline-cold.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evt-collection-07-closure-work-order-inline-warm.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evt-collection-07-closure-work-order-reference-cold.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/c-evt-collection-07-closure-work-order-reference-warm.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-01-root-patch-event.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-02-duplicate-equal-events.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-03-non-public-containing-route.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-04-document-update-continuation.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-05-graceful-termination.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-06-order-representation-inline.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-06-order-representation-reference.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-06-order-representation-reversed.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-07-finite-cyclic-route.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-08-infinite-cycle-gas-retry-first.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-08-infinite-cycle-gas-retry-retry.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-09-late-member-rollback.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`, `work-event-trace-identity-rebind`
- `fixtures/closure/fl-adm-10-unknown-occurrence.yaml` — modified — `invocation-identity-rebind`, `spec-identity-rebind`
- `fixtures/closure/traces/c-clo-04-default-policy-loop-gas.yaml` — modified — `work-event-trace-identity-rebind`
- `fixtures/manifest.yaml` — modified — `actual-semantic-state-result-change`, `spec-identity-rebind`
- `registry/manifest.yaml` — modified — `spec-identity-rebind`
- `release-manifest.yaml` — modified — `actual-semantic-state-result-change`, `spec-identity-rebind`
