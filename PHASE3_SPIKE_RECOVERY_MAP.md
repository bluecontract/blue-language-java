# Phase 3 spike recovery map

Status: **INTERNALLY REVIEWED — SELECTIVE RECOVERY AUTHORIZED**

The prior dirty worktrees remain read-only. This map covers every modified or untracked file present in the preserved Language/Contracts and Coordination spikes. No full patch or tar is authorized for application.

## Controlling decision

Blue Language value, merge, canonicalization, BlueId, provider, reference, and list-overlay semantics remain frozen. Generalization does not reveal an inherited Process Embedded declaration previously hidden by a subtype. The replacement business capability is an ordinary direct Process Embedded contract mutation in the same transition as generalization.

## Accepted bases

- blue-language-java: `03db5ee45f96698a45a3f5556dcc1ef6222d8e6f`
- blue-contract-java: `c6f9c80d0a33c6c209c7ba3d2b8bff89a223fc5f`
- myos-simple: `40ec648b355f3da7331ed89d5fc98152350bf536`

## Review conclusions

- ACCEPT_CANDIDATE: ownership/protection, frozen policy and causal type-write attribution, collector/traversal helpers, exact REBIND evidence, typed demand values, and the ScriptedOperation conformance capability. Each is still gated by focused tests.
- REWRITE_REQUIRED: frozen-current/live-later routing, surface-delta composition, Process Embedded reconciliation, C-EVO generation, Coordination store publication, and resolver integration.
- TEST_ONLY_REFERENCE: spike fixtures and integration scenarios may inform new tests but are not copied wholesale.
- REJECT_DEBUG: all FRAME_DEBUG output.
- REJECT_FAILED_EXPERIMENT: continuation-cache invalidation and refresh path.
- REJECT_BLOCKED_REQUIREMENT: inherited Process Embedded reveal reproducer and conformance expectation.

Known defects to remove before acceptance: selected self-removing work executed twice; current typed-demand comparator orders by kind before the required source/path/value/ordinal tuple; graph reconciliation performs broad environment discovery; Coordination index refresh occurs after in-place publication rather than atomically.

## File-by-file classification

| Repository | State | Classification | Path |
|---|---:|---|---|
| blue-contract-java | ` M` | REWRITE_REQUIRED | `build.gradle` |
| blue-contract-java | `??` | REWRITE_REQUIRED | `src/main/java/blue/coordination/api/ManagedEnvironmentSnapshot.java` |
| blue-contract-java | `??` | REWRITE_REQUIRED | `src/main/java/blue/coordination/api/ManagedOccurrenceEvidenceResolver.java` |
| blue-contract-java | `??` | ACCEPT_CANDIDATE | `src/main/java/blue/coordination/api/ManagedOccurrenceResolution.java` |
| blue-contract-java | `??` | REWRITE_REQUIRED | `src/main/java/blue/coordination/internal/DefaultManagedOccurrenceEvidenceResolver.java` |
| blue-contract-java | ` M` | REWRITE_REQUIRED | `src/main/java/blue/coordination/internal/InMemoryDocumentStore.java` |
| blue-contract-java | `??` | REWRITE_REQUIRED | `src/main/java/blue/coordination/internal/ManagedLineageIndex.java` |
| blue-contract-java | ` M` | ACCEPT_CANDIDATE | `src/main/java/blue/coordination/internal/MultiDocumentPublicationTransaction.java` |
| blue-contract-java | ` M` | REWRITE_REQUIRED | `src/main/java/blue/coordination/internal/SequentialDrainCoordinator.java` |
| blue-contract-java | ` M` | ACCEPT_CANDIDATE | `src/main/java/blue/coordination/processor/ChatWorkflowOperationProcessor.java` |
| blue-contract-java | ` M` | ACCEPT_CANDIDATE | `src/main/java/blue/coordination/processor/OperationProcessor.java` |
| blue-contract-java | ` M` | ACCEPT_CANDIDATE | `src/main/java/blue/coordination/processor/SequentialWorkflowOperationProcessor.java` |
| blue-contract-java | `??` | TEST_ONLY_REFERENCE | `src/test/java/blue/coordination/internal/DefaultManagedOccurrenceEvidenceResolverTest.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-conformance/src/main/java/blue/language/conformance/contracts/ClosureFixtureRuntime.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-conformance/src/main/java/blue/language/conformance/contracts/ContractsFixtureExecutionEngine.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-conformance/src/main/java/blue/language/conformance/contracts/ContractsFixtureHarnessDataSupport.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-conformance/src/main/java/blue/language/conformance/contracts/MockOperation.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-conformance/src/main/java/blue/language/conformance/contracts/MockOperationProcessor.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-conformance/src/main/java/blue/language/conformance/contracts/MockTypeBlueIds.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-01.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-02.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-03.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-04.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-05.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-06.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-07.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-08.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-09.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-10.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/evo/c-evo-14.yaml` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/HARNESS.md` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/resources/blue-contracts-1.0/fixtures/projection-catalog.yaml` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/closure-fixture-schema.yaml` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/closure/c-clo-02-dynamic-finite-cycle.yaml` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/closure/c-clo-11-split-into-two-cycles.yaml` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/closure/c-clo-12-frozen-edge-removal.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-01.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-02.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-03.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-04.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-05.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-06.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-07.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-08.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-09.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-10.yaml` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/evo/c-evo-14.yaml` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/HARNESS.md` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/fixtures/projection-catalog.yaml` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/identity-constructors.yaml` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/registry/manifest.yaml` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-conformance/src/main/resources/blue-contracts-closure-1.0/registry/ScriptedOperation.blue` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/resources/contract/1.0/spec.md` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/tools/generate_closure_fixtures.py` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/tools/refine_closure_fixtures.py` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/tools/regenerate_package.py` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-conformance/src/main/tools/validate_package.py` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `blue-conformance/src/test/java/blue/language/conformance/contracts/closure/CombinedClosureFixtureConformanceTest.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-conformance/src/test/java/blue/language/conformance/contracts/ContractEvolutionFixtureFamilyTest.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/BatchPatchResult.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ChannelRunner.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureAdmissionExecutionSession.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureAttemptResult.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureEvidenceVerifier.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureIdentityService.java` |
| blue-language-java | `??` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureResourceDemand.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureResourceDemandException.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureSuccessResultAssembler.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/DefaultClosureProcessor.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/closure/DocumentStepInput.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ExactNodeDemand.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/GraphChange.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/closure/ManagedDocumentStepProcessor.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/closure/ManagedOccurrenceEvidenceDemand.java` |
| blue-language-java | `??` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/closure/ProcessEmbeddedSurfaceReconciler.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/ContractProcessorRegistry.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/ContractSurfaceCollector.java` |
| blue-language-java | `??` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ContractSurfaceDelta.java` |
| blue-language-java | `??` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ContractSurfaceReconciler.java` |
| blue-language-java | `??` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ContractSurfaceReconciliation.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/DirectContractMutationPreflight.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/DirectProtectedStateMutationGuard.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/DirectSubscriptionSurfaceValidator.java` |
| blue-language-java | ` M` | REJECT_FAILED_EXPERIMENT | `blue-contracts-core/src/main/java/blue/language/processor/DocumentProcessingRuntime.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/DocumentUpdateOccurrence.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/DocumentUpdateRouter.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/EffectiveSubscriptionSurfaceProjector.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/EmbeddedScopePlanner.java` |
| blue-language-java | `??` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/FrozenDispatchContext.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/HandlerProcessor.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ManagedDocumentStepRoute.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ManagedDocumentStepRuntime.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/ManagedProcessEmbeddedPath.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/NoncommittingExecutionException.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/PatchPlanningEngine.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/ProcessingMutationSession.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/ProcessingSnapshotTransaction.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ProcessorInvocationState.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/ProtectedStateGuard.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ScopeExecutor.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ScopeHandlerDispatcher.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ScopeMutationExecutor.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/java/blue/language/processor/ScopePropagationChain.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/SubscriptionSurfaceProjector.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/TypeGeneralizationPolicyResolver.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/main/java/blue/language/processor/util/ProcessorContractConstants.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-contracts-core/src/test/java/blue/language/processor/closure/ClosureResourceDemandTest.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `blue-contracts-core/src/test/java/blue/language/processor/closure/ClosureResultEvidenceTest.java` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `blue-contracts-core/src/test/java/blue/language/processor/closure/FullLifecycleAdmissionTest.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-contracts-core/src/test/java/blue/language/processor/closure/ManagedOccurrenceDemandDiscoveryTest.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `blue-contracts-core/src/test/java/blue/language/processor/closure/ProcessEmbeddedSurfaceReconcilerTest.java` |
| blue-language-java | ` M` | REWRITE_REQUIRED | `docs/processor-contract-matching.md` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `src/test/java/blue/language/processor/ApplicationContractOwnershipTest.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `src/test/java/blue/language/processor/ContractSurfaceReconcilerTest.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `src/test/java/blue/language/processor/DocumentProcessorBoundaryTest.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `src/test/java/blue/language/processor/DocumentProcessorContractSurfaceEvolutionTest.java` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `src/test/java/blue/language/processor/DocumentProcessorGeneralizationTest.java` |
| blue-language-java | `??` | TEST_ONLY_REFERENCE | `src/test/java/blue/language/processor/FrozenDispatchContextTest.java` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `src/test/java/blue/language/processor/PreparedPatchSequenceTest.java` |
| blue-language-java | ` M` | ACCEPT_CANDIDATE | `src/test/java/blue/language/processor/ProtectedStateGuardTest.java` |
| blue-language-java | ` M` | TEST_ONLY_REFERENCE | `src/test/java/blue/language/processor/ScopeMutationServicesTest.java` |
| blue-language-java | `??` | ACCEPT_CANDIDATE | `src/test/java/blue/language/processor/TypeGeneralizationRuntimeSurfaceIntegrationTest.java` |

## Explicit rejected slices outside the file-level primary label

- REJECT_DEBUG — `ScopeExecutor` FRAME_DEBUG stderr output.
- REJECT_FAILED_EXPERIMENT — `DocumentProcessingRuntime.acceptManagedContinuationDocumentReplacement()` and its ScopeExecutor refresh/invalidation call path.
- REJECT_BLOCKED_REQUIREMENT — preserved inherited-Process-Embedded positive reproducer and audit fixture. They remain evidence for the deferred design decision only.

## Recovery order

1. Ownership and protected-state guards.
2. Frozen policy, generated-write attribution, and self-removal routing rewrite.
3. Cohesive surface delta and Process Embedded graph reconciliation.
4. Typed noncommitting resource demands.
5. Generated/replayed C-EVO-01 through C-EVO-23.
6. Exact Contracts checkpoint before any Coordination integration.
