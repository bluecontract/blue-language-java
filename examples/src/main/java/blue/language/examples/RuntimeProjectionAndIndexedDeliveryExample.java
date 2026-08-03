package blue.language.examples;

import blue.language.BlueRuntime;
import blue.language.api.BlueOperationResult;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.IndexedDeliveryDiagnostic;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.SubscriptionSurfaceProjection;
import blue.language.snapshot.FrozenNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Compilable host-side examples for runtime projection and indexed delivery. */
public final class RuntimeProjectionAndIndexedDeliveryExample {

    private RuntimeProjectionAndIndexedDeliveryExample() {
    }

    /**
     * Runs an empty-surface host projection through all three public services.
     *
     * @return deterministic runtime, projection, and delivery observations
     */
    public static Result run() {
        Node root = new Node().name("Managed host example");
        Node event = new Node().properties(
                "kind", new Node().value("example"));
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.asList(1L, "example"));

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                new ContractsExampleSupport.RuntimeWorkProcessor())) {
            BlueContracts contracts = runtime.contracts();
            ProcessorRuntimeAccess access = contracts.runtimeAccess();
            try (DocumentProcessor custom = DocumentProcessor.builder()
                    .runtimeAccess(access)
                    .build()) {
                ResolvedSnapshot snapshot =
                        access.resolveTransient(root);
                SubscriptionDelta initial = contracts
                        .subscriptionSurfaceProjection()
                        .projectInitial(root, 0L, order);
                IndexedDeliveryPreparation prepared = contracts
                        .indexedDeliveryEvaluator()
                        .prepare(
                                root,
                                event,
                                0L,
                                order,
                                initial.added(),
                                Collections
                                        .<ExternalSubscriptionOccurrenceKey>
                                        emptyList());
                ExternalDeliveryPlan compatible = contracts
                        .currentRootDeliveryPlanDeriver(
                                0L,
                                order,
                                initial.added())
                        .derive(root, event);
                return new Result(
                        snapshot.resolvedRoot().getName(),
                        initial.added().size(),
                        prepared.diagnostics().size(),
                        prepared.deliveryPlan().deliveries().size(),
                        custom.administration()
                                .runtimeAccess()
                                .isCurrent(),
                        compatible.exactRuntimeState());
            }
        }
    }

    /**
     * Runs the example from a shell.
     *
     * @param args command-line arguments, which this example ignores
     */
    public static void main(String[] args) {
        Result result = run();
        System.out.println(result.resolvedName()
                + ": subscriptions=" + result.addedSubscriptions()
                + ", deliveries=" + result.deliveryCount());
    }

    /**
     * Builds a custom processor from one exact borrowed runtime generation.
     *
     * @param sourceProcessor processor that owns the runtime generation
     * @param customRegistry custom processor contract registry
     * @param customRegistryIdentity exact non-default registry generation
     *        identity
     * @param gasSchedule custom processor gas schedule
     * @return custom processor borrowing the source runtime generation
     */
    public static DocumentProcessor customProcessor(
            DocumentProcessor sourceProcessor,
            ContractProcessorRegistry customRegistry,
            String customRegistryIdentity,
            GasSchedule gasSchedule) {
        // tag::borrow-runtime-generation[]
        ProcessorRuntimeAccess runtimeAccess =
                sourceProcessor.administration().runtimeAccess();

        DocumentProcessor customProcessor = DocumentProcessor.builder()
                .runtimeAccess(runtimeAccess)
                .runtimeRegistry(customRegistry)
                .runtimeRegistryIdentity(customRegistryIdentity)
                .gasSchedule(gasSchedule)
                .build();
        // end::borrow-runtime-generation[]
        return customProcessor;
    }

    /**
     * Uses the borrowed generation without exposing its snapshot manager.
     *
     * @param runtimeAccess borrowed runtime generation
     * @param exactRoot exact caller-owned Root
     * @param exactReference exact pure reference to materialize
     * @return exhaustive exact-reference outcome
     */
    public static BlueOperationResult<FrozenNode> inspectRuntime(
            ProcessorRuntimeAccess runtimeAccess,
            Node exactRoot,
            FrozenNode exactReference) {
        // tag::inspect-borrowed-runtime[]
        ResolvedSnapshot snapshot =
                runtimeAccess.resolveTransient(exactRoot);
        ResolvedSnapshot preserved =
                runtimeAccess.resolveTransientPreservingPaths(
                        exactRoot, Arrays.asList("/contracts"));
        BlueOperationResult<FrozenNode> materialized =
                runtimeAccess.materializeVerifiedExactReference(
                        exactReference);
        // end::inspect-borrowed-runtime[]
        ExampleSupport.require(snapshot != null && preserved != null,
                "Transient resolutions must return snapshots");
        return materialized;
    }

    /**
     * Obtains the Contracts-owned projection service.
     *
     * @param contracts configured Contracts service
     * @return lifecycle-bound projection service
     */
    public static SubscriptionSurfaceProjection projection(
            BlueContracts contracts) {
        // tag::obtain-subscription-projection[]
        SubscriptionSurfaceProjection projection =
                contracts.subscriptionSurfaceProjection();
        // or: processor.administration().subscriptionSurfaceProjection()
        // end::obtain-subscription-projection[]
        return projection;
    }

    /**
     * Projects the initial active interval surface.
     *
     * @param projection configured projection service
     * @param exactRoot exact admitted Root
     * @return complete initially active intervals
     */
    public static List<SubscriptionDelta.Entry> projectInitial(
            SubscriptionSurfaceProjection projection,
            Node exactRoot) {
        // tag::project-initial-subscriptions[]
        SubscriptionDelta initial = projection.projectInitial(
                exactRoot,
                1L,
                ExternalOrderKey.of(Arrays.asList(100L, "root-created")));

        List<SubscriptionDelta.Entry> activeIntervals = initial.added();
        // end::project-initial-subscriptions[]
        return activeIntervals;
    }

    /**
     * Projects one changed subscription surface.
     *
     * @param projection configured projection service
     * @param resultingExactRoot exact Root after the transition
     * @param activeIntervals complete intervals before the transition
     * @return additions and retirements for the transition
     */
    public static SubscriptionDelta projectUpdate(
            SubscriptionSurfaceProjection projection,
            Node resultingExactRoot,
            List<SubscriptionDelta.Entry> activeIntervals) {
        // tag::project-updated-subscriptions[]
        Set<String> changedPointers = new LinkedHashSet<>(Arrays.asList(
                "/contracts/inbox/subscriptionKey",
                "/lessons/lesson-7/contracts"));

        SubscriptionDelta update = projection.projectUpdate(
                resultingExactRoot,
                activeIntervals,
                changedPointers,
                2L,
                ExternalOrderKey.of(Arrays.asList(140L, "event-42")));
        // end::project-updated-subscriptions[]
        return update;
    }

    /**
     * Re-evaluates exact physical-index candidates into verified evidence.
     *
     * @param contracts configured Contracts service
     * @param exactRoot exact indexed Root
     * @param exactEvent exact incoming event
     * @param completeActiveIntervals complete retained interval surface
     * @return verified delivery preparation and diagnostics
     */
    public static IndexedDeliveryPreparation prepareIndexedDelivery(
            BlueContracts contracts,
            Node exactRoot,
            Node exactEvent,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        // tag::prepare-indexed-delivery[]
        List<ExternalSubscriptionOccurrenceKey> candidates = Arrays.asList(
                ExternalSubscriptionOccurrenceKey.of(
                        "/lessons/lesson-7", "lesson-events"),
                ExternalSubscriptionOccurrenceKey.of(
                        "/", "incoming-orders"));

        IndexedDeliveryPreparation prepared = contracts
                .indexedDeliveryEvaluator()
                .prepare(
                        exactRoot,
                        exactEvent,
                        2L,
                        ExternalOrderKey.of(Arrays.asList(141L, "event-43")),
                        completeActiveIntervals,
                        candidates);

        ExternalDeliveryPlan plan = prepared.deliveryPlan();
        List<IndexedDeliveryDiagnostic> diagnostics = prepared.diagnostics();
        // end::prepare-indexed-delivery[]
        ExampleSupport.require(plan != null && diagnostics != null,
                "Preparation must contain a plan and diagnostics");
        return prepared;
    }

    /**
     * Creates the compatibility deriver over one complete current surface.
     *
     * @param contracts configured Contracts service
     * @param completeActiveIntervals complete retained interval surface
     * @return current-Root compatibility deriver
     */
    public static ExternalDeliveryPlanDeriver currentRootDeriver(
            BlueContracts contracts,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        // tag::current-root-deriver[]
        ExternalDeliveryPlanDeriver deriver = contracts
                .currentRootDeliveryPlanDeriver(
                        2L,
                        ExternalOrderKey.of(Arrays.asList(141L, "event-43")),
                        completeActiveIntervals);
        // end::current-root-deriver[]
        return deriver;
    }

    /** Immutable observations returned by the runnable example. */
    public static final class Result {
        private final String resolvedName;
        private final int addedSubscriptions;
        private final int diagnosticCount;
        private final int deliveryCount;
        private final boolean importedRuntimeCurrent;
        private final boolean compatibilityPlanExact;

        private Result(
                String resolvedName,
                int addedSubscriptions,
                int diagnosticCount,
                int deliveryCount,
                boolean importedRuntimeCurrent,
                boolean compatibilityPlanExact) {
            this.resolvedName = resolvedName;
            this.addedSubscriptions = addedSubscriptions;
            this.diagnosticCount = diagnosticCount;
            this.deliveryCount = deliveryCount;
            this.importedRuntimeCurrent = importedRuntimeCurrent;
            this.compatibilityPlanExact = compatibilityPlanExact;
        }

        /**
         * Returns the name preserved by transient resolution.
         *
         * @return resolved Root name
         */
        public String resolvedName() {
            return resolvedName;
        }

        /**
         * Returns the number of initially active subscriptions.
         *
         * @return number of initially active subscriptions
         */
        public int addedSubscriptions() {
            return addedSubscriptions;
        }

        /**
         * Returns the number of evaluated interval diagnostics.
         *
         * @return number of evaluated interval diagnostics
         */
        public int diagnosticCount() {
            return diagnosticCount;
        }

        /**
         * Returns the number of prepared deliveries.
         *
         * @return number of prepared deliveries
         */
        public int deliveryCount() {
            return deliveryCount;
        }

        /**
         * Reports whether the imported runtime remains current.
         *
         * @return whether the imported runtime remains current
         */
        public boolean importedRuntimeCurrent() {
            return importedRuntimeCurrent;
        }

        /**
         * Reports whether the compatibility plan is certified exact.
         *
         * @return whether the compatibility plan is certified exact
         */
        public boolean compatibilityPlanExact() {
            return compatibilityPlanExact;
        }
    }
}
