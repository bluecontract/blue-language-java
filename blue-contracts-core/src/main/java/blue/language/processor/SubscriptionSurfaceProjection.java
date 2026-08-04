package blue.language.processor;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Projects one exact Root into its deterministic external-subscription delta.
 *
 * <p>The service is a lifecycle-safe façade over the processor's configured
 * {@link SubscriptionSurfaceValidator}. It retains no caller-owned mutable
 * state and exposes none of the projector, loader, registry, or snapshot
 * implementation used to derive the result.</p>
 *
 * <p>An update receives the resulting Root, not a structural before-Root. The
 * retained active intervals are therefore the authoritative prior surface.
 * The validation context reports this through
 * {@link SubscriptionSurfaceValidationContext#usesRetainedIntervalInputSurface()}.
 * It may also contain canonically ordered retained descendant scopes added for
 * conservative route invalidation; the caller's changed-pointer set is never
 * mutated.</p>
 */
public final class SubscriptionSurfaceProjection {

    private static final String VERIFIED_SNAPSHOT_MANAGER_REQUIRED =
            "Subscription surface projection requires a verified "
                    + "ProcessingSnapshotManager";
    private static final String SNAPSHOT_GENERATION_EXPIRED =
            "Subscription surface projection snapshot generation is no longer current";

    private final DocumentProcessor processor;
    private final DocumentProcessorLifecycle lifecycle;

    SubscriptionSurfaceProjection(
            DocumentProcessor processor,
            DocumentProcessorLifecycle lifecycle) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Projects the complete initial subscription surface at one activation
     * boundary.
     *
     * @param exactRoot exact selected Root after activation
     * @param resultingRootRevision non-negative Root revision produced by the
     *        activation
     * @param activationOrderKey external order key immediately before the
     *        subscriptions become eligible
     * @return immutable additions for the initial active surface
     * @throws NullPointerException when the Root or order key is {@code null}
     * @throws IllegalArgumentException when the revision is negative
     * @throws IllegalStateException when the processor is closed or has no
     *         current verified snapshot generation
     * @throws ExecutionEvidenceUnavailableException when exact referenced
     *         content is unavailable
     * @throws SubscriptionSurfaceInvalidException when the Root cannot produce
     *         one valid finite subscription surface
     * @throws PortableLimitExceededException when a portable manifest limit is
     *         exceeded
     * @throws GasLimitExceededException when projection exhausts its gas budget
     */
    public SubscriptionDelta projectInitial(
            Node exactRoot,
            long resultingRootRevision,
            ExternalOrderKey activationOrderKey) {
        return projectUpdate(
                exactRoot,
                Collections.<SubscriptionDelta.Entry>emptyList(),
                Collections.singleton(JsonPointer.ROOT),
                resultingRootRevision,
                activationOrderKey);
    }

    /**
     * Projects an exact resulting Root against the complete retained active
     * interval surface for the affected runtime paths.
     *
     * @param exactRoot exact selected Root after the transition
     * @param priorActiveIntervals complete active interval surface retained at
     *        the input Root revision
     * @param changedRuntimePointers changed absolute Runtime Pointers
     * @param resultingRootRevision non-negative Root revision produced by the
     *        transition
     * @param transitionOrderKey external order key immediately before newly
     *        activated subscriptions become eligible
     * @return immutable additions and retirements for the transition
     * @throws NullPointerException when a required argument or collection
     *         element is {@code null}
     * @throws IllegalArgumentException when the revision is negative
     * @throws IllegalStateException when the processor is closed or has no
     *         current verified snapshot generation
     * @throws ExecutionEvidenceUnavailableException when exact referenced
     *         content is unavailable
     * @throws SubscriptionSurfaceInvalidException when a changed pointer or
     *         resulting subscription surface is invalid
     * @throws PortableLimitExceededException when a portable manifest limit is
     *         exceeded
     * @throws GasLimitExceededException when projection exhausts its gas budget
     */
    public SubscriptionDelta projectUpdate(
            Node exactRoot,
            List<SubscriptionDelta.Entry> priorActiveIntervals,
            Set<String> changedRuntimePointers,
            long resultingRootRevision,
            ExternalOrderKey transitionOrderKey) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            ProcessingSnapshotManager configuredManager =
                    processor.snapshotManager();
            if (configuredManager == null) {
                throw new IllegalStateException(
                        VERIFIED_SNAPSHOT_MANAGER_REQUIRED);
            }
            if (!configuredManager.isTransientStateCurrent()) {
                throw new IllegalStateException(
                        SNAPSHOT_GENERATION_EXPIRED);
            }

            Node tentativeRoot = Objects.requireNonNull(
                    exactRoot, "exactRoot").clone();
            Node inputRoot = tentativeRoot.clone();
            List<SubscriptionDelta.Entry> retainedIntervals =
                    new ArrayList<>(Objects.requireNonNull(
                            priorActiveIntervals,
                            "priorActiveIntervals"));
            Set<String> changedPaths = projectionChangedPaths(
                    retainedIntervals,
                    Objects.requireNonNull(
                            changedRuntimePointers,
                            "changedRuntimePointers"));

            ProcessingSnapshotManager sequence = Objects.requireNonNull(
                    configuredManager.transientSequence(),
                    "transientSequence");
            try {
                Set<String> executableBodyPaths =
                        ExecutableBodyPathCatalog.fromNode(
                                tentativeRoot,
                                ExecutableBodyPathCatalog.authoredNodePaths(
                                        tentativeRoot),
                                processor.registry()
                                        .executableBodyFieldsByType(),
                                sequence);
                ResolvedSnapshot exactSnapshot = Objects.requireNonNull(
                        executableBodyPaths.isEmpty()
                                ? sequence.fromDocumentTransient(
                                        tentativeRoot.clone())
                                : sequence
                                        .fromDocumentTransientPreservingPaths(
                                                tentativeRoot.clone(),
                                                executableBodyPaths),
                        "exactSnapshot");
                ProcessingGasContext gasContext =
                        new ProcessingGasContext(
                                processor.newGasMeter());
                SubscriptionSurfaceValidationContext context =
                        SubscriptionSurfaceValidationContext.builder(
                                        inputRoot,
                                        tentativeRoot,
                                        changedPaths,
                                        processor.gasSchedule())
                                .snapshots(exactSnapshot, exactSnapshot)
                                .activeSubscriptionIntervals(
                                        retainedIntervals)
                                .retainedIntervalInputSurface()
                                .committingInterval(
                                        Objects.requireNonNull(
                                                transitionOrderKey,
                                                "transitionOrderKey"),
                                        resultingRootRevision)
                                .runtimeWorkSessions(
                                        () -> gasContext
                                                .newAdmissionRuntimeWorkSession(
                                                        processor
                                                                .languageRuntimeAccess(),
                                                        sequence))
                                .build();
                return Objects.requireNonNull(
                        processor.subscriptionSurfaceValidator()
                                .validate(context),
                        "subscriptionDelta");
            } finally {
                sequence.releaseTransientState();
            }
        }
    }

    /**
     * Adds retained descendant scopes that require conservative re-projection.
     *
     * <p>This façade receives only the resulting Root, so it cannot inspect a
     * removed or retyped prior Process Embedded declaration. A change inside a
     * direct contract entry of an ancestor might therefore have changed the
     * route to a retained descendant. Adding that descendant scope makes the
     * configured validator evaluate both its retained and resulting surfaces;
     * equal occurrences compare away. The caller-owned pointer set is never
     * mutated.</p>
     */
    private static Set<String> projectionChangedPaths(
            List<SubscriptionDelta.Entry> retainedIntervals,
            Set<String> changedRuntimePointers) {
        List<String> orderedChanges = new ArrayList<>();
        for (String changedPath : changedRuntimePointers) {
            orderedChanges.add(Objects.requireNonNull(
                    changedPath, "changedRuntimePointer"));
        }
        orderedChanges.sort(
                ExternalOrderKey::compareTextCodePoints);
        Set<String> exactChanges = new LinkedHashSet<>(orderedChanges);
        Set<String> result = new LinkedHashSet<>(exactChanges);
        List<String> retainedScopes = new ArrayList<>();
        for (SubscriptionDelta.Entry interval : retainedIntervals) {
            String scopePath = PointerUtils.normalizeScope(
                    Objects.requireNonNull(
                            interval,
                            "priorActiveInterval")
                            .scopePath());
            if (ancestorContractEntryChanged(
                    scopePath, exactChanges)) {
                retainedScopes.add(scopePath);
            }
        }
        retainedScopes.sort(
                ExternalOrderKey::compareTextCodePoints);
        result.addAll(retainedScopes);
        return result;
    }

    /** Reports whether a changed pointer targets a proper ancestor's contract. */
    private static boolean ancestorContractEntryChanged(
            String retainedScopePath,
            Set<String> changedRuntimePointers) {
        List<String> segments = JsonPointer.split(retainedScopePath);
        String ancestorScope = JsonPointer.ROOT;
        for (int index = 0; index < segments.size(); index++) {
            String contractsPath = PointerUtils.resolvePointer(
                    ancestorScope,
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
            for (String changedPath : changedRuntimePointers) {
                try {
                    if (PointerUtils.descendantOrEqual(
                            changedPath, contractsPath)
                            && !changedPath.equals(contractsPath)) {
                        return true;
                    }
                } catch (RuntimeException invalidPointer) {
                    // The configured validator maps malformed pointers.
                }
            }
            ancestorScope = PointerUtils.appendPointer(
                    ancestorScope, segments.get(index));
        }
        return false;
    }
}
