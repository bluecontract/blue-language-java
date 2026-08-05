package blue.language.processor;

import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Creates and refreshes the exact immutable contract frame for a scope. */
final class ScopeFrameFactory {

    private final ProcessorInvocationServices owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ScopeParticipationRegistry participation;

    ScopeFrameFactory(
            ProcessorInvocationServices owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ScopeParticipationRegistry participation) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.participation = Objects.requireNonNull(
                participation, "participation");
    }

    ContractBundle refresh(String scopePath) {
        return refresh(scopePath, true);
    }

    ContractBundle refresh(
            String scopePath,
            boolean preflightSelectedHeaders) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        ProcessingObserver metrics = owner.observer();
        ProcessingObservations.record(
                metrics, ProcessingMetricId.BUNDLE_SCOPE_REFRESHES, 1L);
        long resolvedStart = System.nanoTime();
        FrozenNode selectedScope = selectedAt(normalizedScope);
        if (preflightSelectedHeaders) {
            owner.contractLoader().preflightSelectedContractHeaders(
                    selectedScope);
        }
        FrozenNode resolvedScope;
        try {
            resolvedScope = runtime.resolvedFrozenAt(normalizedScope);
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.BUNDLE_SCOPE_RESOLVED_LOOKUP_NANOS,
                    System.nanoTime() - resolvedStart);
        }
        if (resolvedScope == null) {
            participation.withdraw(normalizedScope);
            return null;
        }
        ContractBundle refreshed = load(
                resolvedScope, normalizedScope, metrics);
        participation.participate(normalizedScope, refreshed);
        return refreshed;
    }

    ContractBundle load(
            FrozenNode resolvedScope,
            String normalizedScope,
            ProcessingObserver metrics) {
        long loadStart = System.nanoTime();
        try {
            FrozenNode selectedScope = selectedAt(normalizedScope);
            FrozenNode recognitionScope = runtime.contractRecognitionScope(
                    selectedScope, resolvedScope);
            ContractBundle loaded = owner.contractLoader().load(
                    selectedScope,
                    recognitionScope,
                    normalizedScope,
                    metrics,
                    execution.contractRecognitionMeter(),
                    "participating-contract-header");
            loaded = EmbeddedScopeEntryPlans.attach(
                    runtime,
                    normalizedScope,
                    resolvedScope,
                    loaded);
            for (EffectiveContractSnapshot snapshot
                    : loaded.effectiveContractSnapshots()) {
                runtime.recordContractSnapshot(snapshot);
            }
            return loaded;
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.BUNDLE_SCOPE_CONTRACT_LOAD_NANOS,
                    System.nanoTime() - loadStart);
        }
    }

    FrozenNode selectedAt(String scopePath) {
        return runtime.selectedFrozenAt(
                ProcessorEngine.normalizeScope(scopePath));
    }

    String nextEmbeddedChild(
            String scopePath,
            ContractBundle bundle,
            Set<String> processed) {
        if (bundle == null) {
            return null;
        }
        Set<String> seenInBundle = new LinkedHashSet<>();
        for (String candidate : bundle.embeddedPaths()) {
            String normalizedCandidate =
                    PointerUtils.assertValidRuntimePointer(candidate);
            String childScope = ProcessorEngine.resolvePointer(
                    scopePath, normalizedCandidate);
            if (childScope.equals(
                    ProcessorEngine.normalizeScope(scopePath))) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Process Embedded path '/' cannot embed its "
                                + "declaring scope");
            }
            if (!seenInBundle.add(childScope)) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Duplicate Process Embedded path: "
                                + normalizedCandidate);
            }
            if (!processed.contains(childScope)) {
                return childScope;
            }
        }
        return null;
    }

    boolean isObjectScope(FrozenNode node) {
        return node != null
                && !node.hasItems()
                && !node.isReferenceOnly()
                && (node.getValue() == null
                || node.getContracts() != null);
    }

    boolean isParticipatingScope(String scopePath, FrozenNode node) {
        if (node == null || node.isReferenceOnly()) {
            return false;
        }
        return JsonPointer.ROOT.equals(
                ProcessorEngine.normalizeScope(scopePath))
                || isObjectScope(node);
    }
}
