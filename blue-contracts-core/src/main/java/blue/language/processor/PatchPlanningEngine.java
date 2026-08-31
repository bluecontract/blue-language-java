package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared immutable patch-planning core.
 *
 * <p>An atomic caller plans every raw patch before one conformance pass. A
 * sequential caller reuses this engine but finishes conformance after each
 * individual patch. Keeping both modes here prevents their patch,
 * generalization, and authoritative-resolution rules from drifting apart.</p>
 */
final class PatchPlanningEngine {

    private final String originScopePath;
    private final FrozenNode initialCanonicalRoot;
    private final FrozenNode initialResolvedRoot;
    private final boolean exactReplacement;
    private final ProcessingSnapshotManager authoritativeSnapshotManager;
    private final ProcessingSnapshotManager invocationEvidenceSnapshotManager;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final UpdateMaterializationMetrics materializationMetrics;
    private final ImmutableJsonPatch.PreparationContext patchPreparation;
    private final ProcessingObserver metrics;
    private final PatchImpactAnalyzer impactAnalyzer;
    private final Set<String> openedScopePaths;
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final boolean initialResolutionComplete;
    private final EmbeddedScopePlan originEmbeddedScopePlan;
    private final boolean strictPlatformInvocation;
    private final ReferenceTransparentPathAccess transparentPathAccess;

    PatchPlanningEngine(String originScopePath,
                        PatchPlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        UpdateMaterializationMetrics materializationMetrics) {
        this(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                NoOpProcessingObserver.INSTANCE,
                true);
    }

    PatchPlanningEngine(String originScopePath,
                        PatchPlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        UpdateMaterializationMetrics materializationMetrics,
                        ProcessingObserver metrics) {
        this(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                metrics,
                true);
    }

    PatchPlanningEngine(String originScopePath,
                        PatchPlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        UpdateMaterializationMetrics materializationMetrics,
                        ProcessingObserver metrics,
                        boolean retainInitialRoots) {
        this.originScopePath = PointerUtils.normalizeScope(
                originScopePath);
        Objects.requireNonNull(planning, "planning");
        FrozenNode canonicalRoot = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenCanonicalRoot()
                : planning.canonicalPlanner().root();
        FrozenNode resolvedRoot = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenResolvedRoot()
                : planning.resolvedPlanner().root();
        this.initialCanonicalRoot = retainInitialRoots ? canonicalRoot : null;
        this.initialResolvedRoot = retainInitialRoots ? resolvedRoot : null;
        this.exactReplacement = planning.exactReplacement();
        this.authoritativeSnapshotManager = planning.authoritativeSnapshotManager();
        this.invocationEvidenceSnapshotManager =
                planning.invocationEvidenceSnapshotManager();
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.materializationMetrics = materializationMetrics;
        this.metrics = metrics != null ? metrics : NoOpProcessingObserver.INSTANCE;
        this.patchPreparation = ImmutableJsonPatch.preparationContext(this.metrics);
        this.impactAnalyzer = new PatchImpactAnalyzer(conformanceEngine,
                conformancePlannerOverride,
                authoritativeSnapshotManager,
                this.metrics);
        this.openedScopePaths =
                new LinkedHashSet<>(planning.openedScopePaths());
        this.openedScopePaths.add(
                PointerUtils.normalizeScope(originScopePath));
        this.executableBodyFieldsByType =
                planning.executableBodyFieldsByType();
        this.initialResolutionComplete =
                planning.isResolutionComplete();
        this.strictPlatformInvocation =
                planning.strictPlatformInvocation();
        this.transparentPathAccess = new ReferenceTransparentPathAccess(
                invocationEvidenceSnapshotManager,
                strictPlatformInvocation,
                executableBodyFieldsByType);
        this.originEmbeddedScopePlan = planning.entryEmbeddedScopePlan(
                this.originScopePath);
    }

    BatchPatchResult planAtomic(List<JsonPatch> patches, boolean buildUpdates) {
        if (initialCanonicalRoot == null || initialResolvedRoot == null) {
            throw new IllegalStateException("Atomic planning roots were not retained");
        }
        List<ImmutableJsonPatch> prepared = preparePatches(patches,
                initialCanonicalRoot,
                initialResolvedRoot);
        return plan(prepared,
                initialCanonicalRoot,
                initialResolvedRoot,
                initialResolutionComplete,
                buildUpdates);
    }

    BatchPatchResult planAtomicInputs(List<PatchInput> patches, boolean buildUpdates) {
        if (initialCanonicalRoot == null || initialResolvedRoot == null) {
            throw new IllegalStateException("Atomic planning roots were not retained");
        }
        List<ImmutableJsonPatch> prepared = preparePatchInputs(patches,
                initialCanonicalRoot,
                initialResolvedRoot);
        return plan(prepared,
                initialCanonicalRoot,
                initialResolvedRoot,
                initialResolutionComplete,
                buildUpdates);
    }

    BatchPatchResult planSequentialStep(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        JsonPatch patch) {
        FrozenNode checkedCanonical = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        FrozenNode checkedResolved = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        ImmutableJsonPatch prepared = preparePatch(patch, checkedCanonical, checkedResolved);
        return planSequentialStep(checkedCanonical, checkedResolved, prepared);
    }

    ImmutableJsonPatch preparePatch(JsonPatch patch,
                                    FrozenNode canonicalRoot,
                                    FrozenNode resolvedRoot) {
        return patchPreparation.prepare(Objects.requireNonNull(patch, "patch"),
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"));
    }

    ImmutableJsonPatch preparePatch(PatchInput patch,
                                    FrozenNode canonicalRoot,
                                    FrozenNode resolvedRoot) {
        return Objects.requireNonNull(patch, "patch").prepare(patchPreparation,
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"));
    }

    BatchPatchResult planSequentialStep(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        ImmutableJsonPatch patch) {
        return planSequentialStep(
                canonicalRoot,
                resolvedRoot,
                initialResolutionComplete,
                patch);
    }

    BatchPatchResult planSequentialStep(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean resolutionComplete,
            ImmutableJsonPatch patch) {
        return plan(Collections.singletonList(Objects.requireNonNull(patch, "patch")),
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"),
                resolutionComplete,
                false);
    }

    List<ImmutableJsonPatch> preparePatches(List<JsonPatch> patches,
                                            FrozenNode canonicalRoot,
                                            FrozenNode resolvedRoot) {
        Objects.requireNonNull(patches, "patches");
        List<ImmutableJsonPatch> prepared = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            prepared.add(preparePatch(patch, canonicalRoot, resolvedRoot));
        }
        return Collections.unmodifiableList(prepared);
    }

    List<ImmutableJsonPatch> preparePatchInputs(List<PatchInput> patches,
                                                FrozenNode canonicalRoot,
                                                FrozenNode resolvedRoot) {
        Objects.requireNonNull(patches, "patches");
        List<ImmutableJsonPatch> prepared = new ArrayList<>(patches.size());
        for (PatchInput patch : patches) {
            prepared.add(preparePatch(patch, canonicalRoot, resolvedRoot));
        }
        return Collections.unmodifiableList(prepared);
    }

    private BatchPatchResult plan(List<ImmutableJsonPatch> patches,
                                  FrozenNode initialCanonical,
                                  FrozenNode initialResolved,
                                  boolean initialResolutionComplete,
                                  boolean buildUpdates) {
        Objects.requireNonNull(patches, "patches");
        TypeGeneralizationPolicyResolver.FrozenPolicy frozenGeneralizationPolicy =
                null;
        long planningStart = System.nanoTime();
        FrozenNode workingCanonical = initialCanonical;
        FrozenNode workingResolved = initialResolved;
        boolean workingResolutionComplete = initialResolutionComplete;
        PatchImpact.FallbackReason authoritativeFallbackReason = null;
        List<BatchPatchRecord> records = new ArrayList<>();
        List<ImmutableJsonPatch> preparedPatches = new ArrayList<>(patches.size());
        for (ImmutableJsonPatch prepared : patches) {
            Objects.requireNonNull(prepared, "patch");
            /*
             * Preserve the collapsed mutation boundary first. In particular,
             * a cyclic MASTER#index ancestor must be rejected before exact
             * evidence can open it. Only after that check may this one patch
             * demand the ordinary reference ancestors it actually traverses.
             */
            ImmutablePatchPlanner.forFrozen(workingCanonical)
                    .validateMutationPath(prepared.path());
            PatchBase patchBase = materializePatchBase(
                    workingCanonical,
                    workingResolved,
                    workingResolutionComplete,
                    prepared.normalizedPath());
            workingCanonical = patchBase.canonical;
            workingResolved = patchBase.resolved;
            workingResolutionComplete =
                    patchBase.resolutionComplete;
            if (frozenGeneralizationPolicy == null) {
                frozenGeneralizationPolicy =
                        TypeGeneralizationPolicyResolver.freeze(
                                workingResolved, originScopePath);
            }
            ImmutableJsonPatch authoredPrepared = prepared;
            prepared = ProcessorOwnedContractsStatePreserver.preserve(
                    originScopePath,
                    prepared,
                    workingCanonical,
                    workingResolved);
            boolean processorStatePreserved = prepared != authoredPrepared;
            if (ProcessorOwnedContractsStatePreserver
                    .requiresExactReplacement(
                            originScopePath,
                            prepared.op(),
                            prepared.normalizedPath())) {
                prepared = prepared.withExactValueWrite();
            }
            preparedPatches.add(processorStatePreserved
                    ? selectedDocumentPatch(prepared)
                    : prepared);
            ImmutablePatchPlanner canonicalPlanner = ImmutablePatchPlanner.forFrozen(workingCanonical);
            boolean exactValueWrite = exactReplacement
                    || prepared.exactValueWrite();
            ImmutablePatchPlanner.PatchPlan canonicalPlan = exactValueWrite
                    ? canonicalPlanner.planWithExactReplacement(originScopePath, prepared)
                    : canonicalPlanner.plan(originScopePath, prepared);
            ImmutableJsonPatch resolvedPatch = resolveProcessorManagedValue(
                    prepared, canonicalPlan);
            ImmutablePatchPlanner resolvedPlanner = ImmutablePatchPlanner.forFrozen(workingResolved);
            boolean objectMemberTarget = targetsObjectMember(
                    resolvedPlanner,
                    resolvedPatch.path());
            ImmutablePatchPlanner.PatchPlan resolvedPlan = exactValueWrite
                    ? resolvedPlanner.planWithExactReplacement(originScopePath, resolvedPatch)
                    : resolvedPlanner.plan(originScopePath, resolvedPatch);
            PatchImpact impact = impactAnalyzer.analyze(exactValueWrite,
                    workingCanonical,
                    workingResolved,
                    canonicalPlan,
                    resolvedPlan,
                    resolvedPatch);
            if (impact.resolvedScalarMetadataPreservationRequired()) {
                resolvedPlan = resolvedPlanner.planWithPreservedResolvedScalarMetadata(
                        originScopePath, prepared);
            }
            if (exactReplacement
                    && !impact.localResolutionProvenSafe()
                    && authoritativeFallbackReason == null) {
                authoritativeFallbackReason = impact.fallbackReason();
            }
            BatchPatchRecord record = new BatchPatchRecord(resolvedPatch,
                    canonicalPlan,
                    resolvedPlan,
                    objectMemberTarget,
                    impact,
                    isProcessorManagedConformanceBypass(canonicalPlan));
            records.add(record);
            workingCanonical = canonicalPlan.root();
            workingResolved = resolvedPlan.root();
        }
        if (frozenGeneralizationPolicy == null) {
            frozenGeneralizationPolicy =
                    TypeGeneralizationPolicyResolver.freeze(
                            initialResolved, originScopePath);
        }
        long patchPlanningNanos = System.nanoTime() - planningStart;

        long conformanceStart = System.nanoTime();
        FrozenNode preConformanceCanonical = workingCanonical;
        FrozenNode preConformanceResolved = workingResolved;
        ConformancePlanningResult conformancePlanning = planBatchConformance(
                workingCanonical,
                workingResolved,
                records,
                frozenGeneralizationPolicy);
        ConformancePlan conformancePlan = conformancePlanning.plan();
        long conformanceNanos = System.nanoTime() - conformanceStart;
        FrozenNode finalCanonical = conformancePlan.canonicalRoot() != null
                ? conformancePlan.canonicalRoot()
                : workingCanonical;
        FrozenNode finalResolved = conformancePlan.root();
        boolean finalResolutionComplete =
                workingResolutionComplete;
        boolean fullSnapshotResolution = exactReplacement
                && (authoritativeFallbackReason != null || !conformancePlan.fullSnapshotRebuildAvoidable());
        if (fullSnapshotResolution) {
            if (authoritativeSnapshotManager == null) {
                throw new IllegalStateException("Authoritative snapshot resolution is unavailable");
            }
            PatchImpact.FallbackReason reason = authoritativeFallbackReason != null
                    ? authoritativeFallbackReason
                    : PatchImpact.FallbackReason.DEPENDENCY_INDEX_MISSING_OR_STALE;
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_SNAPSHOT_FALLBACKS, 1L);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_SNAPSHOT_FALLBACK_REASON,
                    1L,
                    ProcessingObservationContext.of(
                            ProcessingObservationDimension.FALLBACK_REASON,
                            reason.name()));
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_CANONICAL_ROOT_MATERIALIZATIONS, 1L);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_FROZEN_ROOT_TO_NODE_MATERIALIZATIONS, 1L);
            ResolvedSnapshot authoritative = strictPlatformInvocation
                    ? DocumentProcessingRuntime
                            .resolveCanonicalTransientIncludingTypeContracts(
                                    authoritativeSnapshotManager,
                                    finalCanonical,
                                    openedScopePaths,
                                    executableBodyFieldsByType)
                    : DocumentProcessingRuntime.resolveCanonicalTransient(
                            authoritativeSnapshotManager,
                            finalCanonical,
                            openedScopePaths,
                            executableBodyFieldsByType);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_RESOLVED_ROOT_MATERIALIZATIONS, 1L);
            finalCanonical = authoritative.frozenCanonicalRoot();
            finalResolved = authoritative.frozenResolvedRoot();
            finalResolutionComplete =
                    authoritative.isResolutionComplete();
        } else if (exactReplacement) {
            for (BatchPatchRecord record : records) {
                if (record.impact().localResolutionProvenSafe()) {
                    ProcessingObservations.record(metrics,
                            ProcessingMetricId.INCREMENTAL_SNAPSHOT_RESOLUTIONS, 1L);
                    if (record.impact().kind() == PatchImpact.Kind.PROCESSOR_MANAGED_STATE) {
                        ProcessingObservations.record(metrics,
                                ProcessingMetricId.PROCESSOR_MANAGED_MARKER_INCREMENTAL_RESOLUTIONS,
                                1L);
                    }
                    ProcessingObservations.record(metrics,
                            ProcessingMetricId.INCREMENTAL_BOUNDARY_PATH_DEPTH,
                            record.impact().path().depth());
                    ProcessingObservations.record(metrics,
                            ProcessingMetricId.INCREMENTAL_BOUNDARY_NODE_COUNT, 1L);
                }
            }
        }
        if (containsApplicationPatch(records)) {
            ProtectedStateGuard.verifyUnchanged(
                    initialCanonical,
                    initialResolved,
                    finalCanonical,
                    finalResolved,
                    wholeEmbeddedChildApplicationPatches(
                            records),
                    invocationEvidenceSnapshotManager,
                    openedScopePaths);
        }
        List<BatchPatchResult.GeneralizationMetadataWrite> metadataWrites =
                conformancePlanning.metadataWrites();
        BatchPatchResult.UpdatePlan updatePlan = new BatchPatchResult.UpdatePlan(records,
                preConformanceCanonical,
                preConformanceResolved,
                finalResolved,
                metadataWrites,
                true);
        long buildUpdatesNanos = 0L;
        List<DocumentUpdateData> updates = null;
        if (buildUpdates) {
            long buildUpdatesStart = System.nanoTime();
            updates = updatePlan.build(materializationMetrics);
            buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
        }
        return new BatchPatchResult(finalCanonical,
                finalResolved,
                updates,
                updatePlan,
                preparedPatches,
                metadataWrites,
                finalResolutionComplete,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    private PatchBase materializePatchBase(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean resolutionComplete,
            String patchPath) {
        if (invocationEvidenceSnapshotManager == null) {
            return new PatchBase(
                    canonicalRoot, resolvedRoot, resolutionComplete);
        }
        FrozenNode expanded = transparentPathAccess
                .materializePatchAncestors(
                        canonicalRoot,
                        Collections.singletonList(patchPath));
        if (expanded == canonicalRoot) {
            return new PatchBase(
                    canonicalRoot, resolvedRoot, resolutionComplete);
        }
        ResolvedSnapshot snapshot = strictPlatformInvocation
                ? DocumentProcessingRuntime
                        .resolveCanonicalTransientIncludingTypeContracts(
                                invocationEvidenceSnapshotManager,
                                expanded,
                                openedScopePaths,
                                executableBodyFieldsByType)
                : DocumentProcessingRuntime.resolveCanonicalTransient(
                        invocationEvidenceSnapshotManager,
                        expanded,
                        openedScopePaths,
                        executableBodyFieldsByType);
        return new PatchBase(
                expanded,
                snapshot.frozenResolvedRoot(),
                snapshot.isResolutionComplete());
    }

    private static final class PatchBase {
        private final FrozenNode canonical;
        private final FrozenNode resolved;
        private final boolean resolutionComplete;

        private PatchBase(
                FrozenNode canonical,
                FrozenNode resolved,
                boolean resolutionComplete) {
            this.canonical = Objects.requireNonNull(
                    canonical, "canonical");
            this.resolved = Objects.requireNonNull(
                    resolved, "resolved");
            this.resolutionComplete = resolutionComplete;
        }
    }


    private boolean containsApplicationPatch(List<BatchPatchRecord> records) {
        for (BatchPatchRecord record : records) {
            if (!record.processorManagedConformanceBypass()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Captures the target container shape before the patch mutates it so
     * Document Update rendering can distinguish object-member upsert
     * semantics from positional list semantics.
     */
    private boolean targetsObjectMember(
            ImmutablePatchPlanner planner,
            ParsedJsonPointer path) {
        if (path.isRoot()) {
            return false;
        }
        FrozenNode parent = planner.read(path.parent());
        if (parent == null || !parent.hasItems()) {
            return parent != null;
        }
        String member = path.segments().get(
                path.segments().size() - 1);
        return BlueLanguageConstants.OBJECT_VALUE.equals(member)
                || ProcessorContractConstants.KEY_CONTRACTS.equals(member);
    }

    private Set<String> wholeEmbeddedChildApplicationPatches(
            List<BatchPatchRecord> records) {
        /*
         * Boundary validation already limits an ancestor to an exact
         * immediate-child-root operation. Use the immutable concrete plan
         * frozen when this invocation entered the scope; later patches must
         * not reopen changed collection membership.
         */
        Set<String> result = new LinkedHashSet<>();
        for (BatchPatchRecord record : records) {
            if (record.processorManagedConformanceBypass()) {
                continue;
            }
            if (originEmbeddedScopePlan == null
                    || !originEmbeddedScopePlan.scopePath().equals(
                            PointerUtils.normalizeScope(
                                    record.originScope()))) {
                continue;
            }
            String target =
                    PointerUtils.normalizePointer(record.path());
            if (originEmbeddedScopePlan.concreteChildPaths()
                    .contains(target)) {
                result.add(target);
            }
        }
        return result;
    }

    private ConformancePlanningResult planBatchConformance(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            List<BatchPatchRecord> records,
            TypeGeneralizationPolicyResolver.FrozenPolicy
                    frozenGeneralizationPolicy) {
        boolean hasOverride = conformancePlannerOverride != null && conformancePlannerOverride.applies();
        if (conformanceEngine == null && !hasOverride) {
            return ConformancePlanningResult.unchanged(
                    canonicalRoot, resolvedRoot);
        }
        List<String> changedPaths = new ArrayList<>();
        List<ConformanceChangedPath> changedPathRecords = new ArrayList<>();
        for (BatchPatchRecord record : records) {
            if (record.processorManagedConformanceBypass()) {
                continue;
            }
            if (record.impact().localResolutionProvenSafe()) {
                continue;
            }
            if (hasTypedNodeBetweenOriginAndPath(resolvedRoot, record.originScope(), record.path())) {
                changedPaths.add(record.path());
                changedPathRecords.add(new ConformanceChangedPath(record.path(), record.originScope()));
            }
        }
        if (changedPaths.isEmpty()) {
            return ConformancePlanningResult.unchanged(
                    canonicalRoot, resolvedRoot);
        }
        ProcessingObservations.record(metrics,
                ProcessingMetricId.CONFORMANCE_PLANS, 1L);
        ConformancePlan plan;
        if (hasOverride) {
            plan = conformancePlannerOverride.plan(
                    canonicalRoot, resolvedRoot, changedPathRecords);
        } else {
            try {
                plan = conformanceEngine
                        .planGeneralizationPreservingPaths(
                                canonicalRoot,
                                resolvedRoot,
                                changedPaths,
                                preservedExecutableBodies(
                                        canonicalRoot, resolvedRoot));
            } catch (ProcessorFailureException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new ProcessorFailureException(
                        ProcessorErrorCategory.TypeGeneralizationFailure,
                        "GeneralizationNoValidType: " + ex.getMessage(),
                        ex);
            }
        }
        String originScope = originScopeForGeneratedUpdate(records);
        TypeGeneralizationPolicyResolver.enforceScopeBoundary(
                originScope, plan.changedPaths());
        TypeGeneralizationPolicyResolver.enforce(
                conformanceEngine,
                plan.root(),
                plan.changedPaths(),
                originScope,
                frozenGeneralizationPolicy,
                changesApplicationContracts(records));
        FrozenNode finalCanonical = plan.canonicalRoot() != null
                ? plan.canonicalRoot()
                : canonicalRoot;
        List<BatchPatchResult.GeneralizationMetadataWrite> metadataWrites =
                GeneralizationMetadataAttributor.attribute(
                        finalCanonical,
                        plan.root(),
                        plan.changedPaths(),
                        records,
                        this::probeConformanceAtRecord);
        return new ConformancePlanningResult(plan, metadataWrites);
    }

    private boolean changesApplicationContracts(
            List<BatchPatchRecord> records) {
        for (BatchPatchRecord record : records) {
            if (record.processorManagedConformanceBypass()) {
                continue;
            }
            String contractsPath = ProcessorEngine.resolvePointer(
                    record.originScope(),
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
            String patchPath = PointerUtils.normalizePointer(record.path());
            if (PointerUtils.descendantOrEqual(patchPath, contractsPath)) {
                return true;
            }
            if (!PointerUtils.descendantOrEqual(contractsPath, patchPath)) {
                continue;
            }
            List<String> patchSegments = JsonPointer.split(patchPath);
            List<String> contractSegments = JsonPointer.split(contractsPath);
            String relativeContracts = JsonPointer.toPointer(
                    contractSegments.subList(
                            patchSegments.size(),
                            contractSegments.size()));
            FrozenNode before = record.beforeAtPatchTime();
            FrozenNode after = record.afterAtPatchTime();
            FrozenNode beforeContracts = before != null
                    ? before.at(relativeContracts)
                    : null;
            FrozenNode afterContracts = after != null
                    ? after.at(relativeContracts)
                    : null;
            if (!sameIdentity(beforeContracts, afterContracts)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameIdentity(FrozenNode left, FrozenNode right) {
        return left == null
                ? right == null
                : right != null && left.blueId().equals(right.blueId());
    }

    private ConformancePlan probeConformanceAtRecord(
            BatchPatchRecord record) {
        FrozenNode canonicalRoot = record.canonicalPlan().root();
        FrozenNode resolvedRoot = record.resolvedPlan().root();
        if (record.processorManagedConformanceBypass()
                || record.impact().localResolutionProvenSafe()
                || !hasTypedNodeBetweenOriginAndPath(
                        resolvedRoot,
                        record.originScope(),
                        record.path())) {
            return ConformancePlan.unchanged(
                    canonicalRoot, resolvedRoot);
        }
        if (conformancePlannerOverride != null
                && conformancePlannerOverride.applies()) {
            return conformancePlannerOverride.plan(
                    canonicalRoot,
                    resolvedRoot,
                    Collections.singletonList(
                            new ConformanceChangedPath(
                                    record.path(),
                                    record.originScope())));
        }
        if (conformanceEngine == null) {
            return ConformancePlan.unchanged(
                    canonicalRoot, resolvedRoot);
        }
        return conformanceEngine.planGeneralizationPreservingPaths(
                        canonicalRoot,
                        resolvedRoot,
                        Collections.singletonList(record.path()),
                        preservedExecutableBodies(
                                canonicalRoot, resolvedRoot));
    }

    private Set<String> preservedExecutableBodies(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        Set<String> preservedBodies =
                new LinkedHashSet<>(
                        DocumentProcessingRuntime.executableBodyPaths(
                                /*
                                 * Reference-only contracts maps and contract
                                 * entries have no direct type header in the
                                 * canonical lane. The effective lane has
                                 * already resolved those headers while the
                                 * executable subtree remains deferred, so it
                                 * is the authoritative source for locating
                                 * paths that conformance must not demand.
                                 */
                                resolvedRoot,
                                openedScopePaths,
                                executableBodyFieldsByType));
        if (invocationEvidenceSnapshotManager == null) {
            return preservedBodies;
        }
        Node canonicalDocument = canonicalRoot.toNode();
        preservedBodies.addAll(
                strictPlatformInvocation
                        ? ExecutableBodyPathCatalog
                                .fromNodeIncludingTypeContracts(
                                        canonicalDocument,
                                        openedScopePaths,
                                        executableBodyFieldsByType,
                                        invocationEvidenceSnapshotManager)
                        : ExecutableBodyPathCatalog
                                .fromNodeDirectContracts(
                                        canonicalDocument,
                                        openedScopePaths,
                                        executableBodyFieldsByType,
                                        invocationEvidenceSnapshotManager));
        if (strictPlatformInvocation) {
            preservedBodies.addAll(
                    ExecutableBodyPathCatalog.ordinaryReferencePaths(
                            canonicalDocument,
                            openedScopePaths));
        }
        preservedBodies.addAll(
                ExecutableBodyPathCatalog.processorStateReferencePaths(
                        canonicalDocument,
                        openedScopePaths));
        return preservedBodies;
    }

    private boolean hasTypedNodeBetweenOriginAndPath(FrozenNode resolvedRoot, String originScope, String changedPath) {
        ImmutablePatchPlanner planner = ImmutablePatchPlanner.forFrozen(resolvedRoot);
        String normalizedOrigin = PointerUtils.normalizeScope(originScope);
        String current = PointerUtils.normalizePointer(changedPath);
        while (true) {
            FrozenNode node = planner.read(current);
            if (hasTypeMetadata(node)) {
                return true;
            }
            if (current.equals(normalizedOrigin)
                    || JsonPointer.ROOT.equals(current)) {
                return false;
            }
            current = parentPointer(current);
        }
    }

    private boolean hasTypeMetadata(FrozenNode node) {
        return node != null
                && (node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null);
    }

    private String parentPointer(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            return JsonPointer.ROOT;
        }
        return JsonPointer.toPointer(segments.subList(0, segments.size() - 1));
    }

    private String originScopeForGeneratedUpdate(List<BatchPatchRecord> records) {
        return records.isEmpty()
                ? JsonPointer.ROOT
                : records.get(0).originScope();
    }

    private boolean isProcessorManagedConformanceBypass(ImmutablePatchPlanner.PatchPlan result) {
        String relativePath = PointerUtils.relativizePointer(result.originScope(), result.path());
        String initialized = ProcessorPointerConstants.RELATIVE_INITIALIZED;
        return PointerUtils.descendantOrEqual(relativePath, initialized);
    }

    private ImmutableJsonPatch resolveProcessorManagedValue(
            ImmutableJsonPatch patch,
            ImmutablePatchPlanner.PatchPlan canonicalPlan) {
        if (!exactReplacement
                || authoritativeSnapshotManager == null
                || patch.op() == JsonPatch.Op.REMOVE
                || !isProcessorManagedConformanceBypass(canonicalPlan)) {
            return patch;
        }
        ResolvedSnapshot resolvedValue = authoritativeSnapshotManager.fromDocumentTransient(
                patch.canonicalValue().toNode());
        return patch.withResolvedValue(resolvedValue.frozenResolvedRoot());
    }

    private ImmutableJsonPatch selectedDocumentPatch(
            ImmutableJsonPatch plannedPatch) {
        FrozenNode selectedValue = FrozenNode.authoredValueInModeOf(
                plannedPatch.canonicalValue(),
                plannedPatch.resolvedValue());
        return plannedPatch.withResolvedValue(selectedValue);
    }

    private static final class ConformancePlanningResult {
        private final ConformancePlan plan;
        private final List<BatchPatchResult.GeneralizationMetadataWrite>
                metadataWrites;

        private ConformancePlanningResult(
                ConformancePlan plan,
                List<BatchPatchResult.GeneralizationMetadataWrite>
                        metadataWrites) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.metadataWrites = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(
                            metadataWrites, "metadataWrites")));
        }

        private static ConformancePlanningResult unchanged(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot) {
            return new ConformancePlanningResult(
                    ConformancePlan.unchanged(
                            canonicalRoot, resolvedRoot),
                    Collections.<BatchPatchResult.GeneralizationMetadataWrite>
                            emptyList());
        }

        private ConformancePlan plan() {
            return plan;
        }

        private List<BatchPatchResult.GeneralizationMetadataWrite>
        metadataWrites() {
            return metadataWrites;
        }
    }
}
