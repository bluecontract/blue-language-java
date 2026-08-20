package blue.language.processor;

import blue.language.snapshot.FrozenNode;

import java.util.Map;
import java.util.Objects;

/**
 * Freezes one scope's concrete embedded membership for the current event.
 *
 * <p>Structural bundles retain declarations only. This service expands a
 * declaration against the complete immutable entry scope, publishes the
 * resulting plan only after validation succeeds, and reuses it for every
 * later consumer in the invocation.</p>
 */
final class EmbeddedScopeEntryPlans {

    private EmbeddedScopeEntryPlans() {
    }

    /** Attaches the scope's write-once entry plan to an invocation-local bundle. */
    static ContractBundle attach(
            DocumentProcessingRuntime runtime,
            String scopePath,
            FrozenNode effectiveScope,
            ContractBundle bundle) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(bundle, "bundle");
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        ScopeRuntimeContext context = runtime.scope(normalizedScope);
        EmbeddedScopePlan plan;
        if (context.hasEntryEmbeddedScopePlan()) {
            plan = context.entryEmbeddedScopePlan();
        } else if (!bundle.hasProcessEmbedded()) {
            context.freezeEntryEmbeddedScopePlan(null);
            plan = null;
        } else {
            EmbeddedScopeDeclaration declaration =
                    bundle.embeddedScopeDeclaration();
            ProcessingSnapshotManager manager =
                    runtime.currentSnapshotManager();
            EmbeddedScopePlanner planner = manager != null
                    ? new EmbeddedScopePlanner(
                            manager::materializeVerifiedExactReference)
                    : new EmbeddedScopePlanner();
            plan = planner.planForRevisionBoundEvent(
                    Objects.requireNonNull(
                            effectiveScope, "effectiveScope"),
                    normalizedScope,
                    declaration.explicitPaths(),
                    declaration.collectionPaths(),
                    runtime.gasMeter());
            context.freezeEntryEmbeddedScopePlan(plan);
        }
        return bundle.withEmbeddedScopePlan(plan);
    }

    /**
     * Attaches a declaration-complete plan for an independently executed
     * managed Root while keeping every selected managed child opaque.
     */
    static ContractBundle attachManagedRoot(
            DocumentProcessingRuntime runtime,
            String scopePath,
            FrozenNode effectiveScope,
            ContractBundle bundle,
            Map<String, String> expectedManagedBlueIdsByPath) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(
                expectedManagedBlueIdsByPath,
                "expectedManagedBlueIdsByPath");
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        ScopeRuntimeContext context = runtime.scope(normalizedScope);
        EmbeddedScopePlan plan;
        if (context.hasEntryEmbeddedScopePlan()) {
            plan = context.entryEmbeddedScopePlan();
        } else if (!bundle.hasProcessEmbedded()) {
            if (!expectedManagedBlueIdsByPath.isEmpty()) {
                throw new InvalidExecutionEvidenceException(
                        "Managed occurrences are not covered by an effective "
                                + "Process Embedded declaration",
                        ProcessorErrorCategory.SubscriptionSurfaceInvalid);
            }
            context.freezeEntryEmbeddedScopePlan(null);
            plan = null;
        } else {
            EmbeddedScopeDeclaration declaration =
                    bundle.embeddedScopeDeclaration();
            plan = new EmbeddedScopePlanner()
                    .planForOpaqueManagedRoot(
                            Objects.requireNonNull(
                                    effectiveScope, "effectiveScope"),
                            normalizedScope,
                            declaration.explicitPaths(),
                            declaration.collectionPaths(),
                            expectedManagedBlueIdsByPath,
                            runtime.gasMeter());
            context.freezeEntryEmbeddedScopePlan(plan);
        }
        return bundle.withEmbeddedScopePlan(plan);
    }
}
