package blue.coordination.closure;

import blue.contracts.closure.CanonicalOrders;
import blue.contracts.closure.AffectedClosureSnapshot;
import blue.contracts.closure.ClosureCommitPlan;
import blue.contracts.closure.ClosureProcessResult;
import blue.contracts.closure.ComponentSnapshot;
import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ManagedOccurrenceBinding;
import blue.contracts.closure.PublicEventOccurrence;
import blue.contracts.closure.ResultingComponent;
import blue.contracts.closure.ResultingDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reference all-or-nothing in-memory adapter with a real optimistic
 * compare-and-swap recheck. It is deliberately storage-neutral, not a
 * production database design.
 */
public final class InMemoryClosureStore {
    private long storeVersion;
    private String closureIdentity;
    private long graphGeneration;
    private Map<DocumentId, ResultingDocument> documents;
    private List<ResultingComponent> components;
    private List<ManagedOccurrenceBinding> occurrences;
    private String occurrenceBindingSetIdentity;
    private final ManagedOccurrenceBinding.BindingSetIdentityFactory
            bindingSetIdentityFactory;
    private final AffectedClosureSnapshot.ClosureIdentityFactory
            closureIdentityFactory;
    private List<ClosureProcessResult.GraphChange> graphChanges =
            new ArrayList<ClosureProcessResult.GraphChange>();
    private String graphChangesIdentity;
    private List<ClosureProcessResult.CheckpointWrite> checkpointWrites =
            new ArrayList<ClosureProcessResult.CheckpointWrite>();
    private String checkpointWritesIdentity;
    private List<ClosureProcessResult.SubscriptionDelta> subscriptionDeltas =
            new ArrayList<ClosureProcessResult.SubscriptionDelta>();
    private String subscriptionDeltasIdentity;
    private List<PublicEventOccurrence> events =
            new ArrayList<PublicEventOccurrence>();
    private String publicEventsIdentity;
    private ClosureCommitReceipt receipt;

    public InMemoryClosureStore(
            String closureIdentity,
            long graphGeneration,
            Map<DocumentId, ResultingDocument> documents,
            List<ResultingComponent> components,
            List<ManagedOccurrenceBinding> occurrences,
            String occurrenceBindingSetIdentity,
            ManagedOccurrenceBinding.BindingSetIdentityFactory
                    bindingSetIdentityFactory,
            AffectedClosureSnapshot.ClosureIdentityFactory
                    closureIdentityFactory) {
        this.graphGeneration = CanonicalOrders.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.documents = copyDocuments(documents);
        this.components = copy(components, "components");
        this.bindingSetIdentityFactory = Objects.requireNonNull(
                bindingSetIdentityFactory, "bindingSetIdentityFactory");
        this.closureIdentityFactory = Objects.requireNonNull(
                closureIdentityFactory, "closureIdentityFactory");
        this.occurrences = copy(occurrences, "occurrences");
        String recomputedBindingSetIdentity = recomputeOccurrenceBindingSetIdentity(
                this.occurrences);
        if (!recomputedBindingSetIdentity.equals(Objects.requireNonNull(
                occurrenceBindingSetIdentity, "occurrenceBindingSetIdentity"))) {
            throw new IllegalArgumentException("occurrenceBindingSetIdentity mismatch");
        }
        this.occurrenceBindingSetIdentity = recomputedBindingSetIdentity;
        String recomputedClosureIdentity = recomputeClosureIdentity(
                this.graphGeneration,
                this.documents,
                this.components,
                this.occurrenceBindingSetIdentity);
        if (!recomputedClosureIdentity.equals(Objects.requireNonNull(
                closureIdentity, "closureIdentity"))) {
            throw new IllegalArgumentException("closureIdentity mismatch");
        }
        this.closureIdentity = recomputedClosureIdentity;
    }

    public synchronized ClosureStoreTransaction begin() {
        return new Transaction();
    }

    public synchronized long graphGeneration() {
        return graphGeneration;
    }

    public synchronized String closureIdentity() {
        return closureIdentity;
    }

    public synchronized Map<DocumentId, ResultingDocument> documents() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<DocumentId, ResultingDocument>(documents));
    }

    public synchronized List<ResultingComponent> components() {
        return immutableCopy(components);
    }

    public synchronized List<ManagedOccurrenceBinding> occurrences() {
        return immutableCopy(occurrences);
    }

    public synchronized String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    public synchronized List<ClosureProcessResult.GraphChange> graphChanges() {
        return immutableCopy(graphChanges);
    }

    public synchronized String graphChangesIdentity() {
        return graphChangesIdentity;
    }

    public synchronized List<ClosureProcessResult.CheckpointWrite> checkpointWrites() {
        return immutableCopy(checkpointWrites);
    }

    public synchronized String checkpointWritesIdentity() {
        return checkpointWritesIdentity;
    }

    public synchronized List<ClosureProcessResult.SubscriptionDelta> subscriptionDeltas() {
        return immutableCopy(subscriptionDeltas);
    }

    public synchronized String subscriptionDeltasIdentity() {
        return subscriptionDeltasIdentity;
    }

    public synchronized List<PublicEventOccurrence> events() {
        return immutableCopy(events);
    }

    public synchronized String publicEventsIdentity() {
        return publicEventsIdentity;
    }

    public synchronized ClosureCommitReceipt receipt() {
        return receipt;
    }

    private final class Transaction implements ClosureStoreTransaction {
        private final long baseStoreVersion = storeVersion;
        private final String baseClosureIdentity = closureIdentity;
        private final long baseGraphGeneration = graphGeneration;
        private final Map<DocumentId, ResultingDocument> baseDocuments =
                new LinkedHashMap<DocumentId, ResultingDocument>(documents);
        private final List<ResultingComponent> baseComponents =
                new ArrayList<ResultingComponent>(components);
        private final List<ManagedOccurrenceBinding> baseOccurrences =
                new ArrayList<ManagedOccurrenceBinding>(occurrences);
        private final String baseOccurrenceBindingSetIdentity =
                occurrenceBindingSetIdentity;

        private final Map<DocumentId, ResultingDocument> stagedDocuments =
                new LinkedHashMap<DocumentId, ResultingDocument>(documents);
        private List<ResultingComponent> stagedComponents =
                new ArrayList<ResultingComponent>(components);
        private List<ManagedOccurrenceBinding> stagedOccurrences =
                new ArrayList<ManagedOccurrenceBinding>(occurrences);
        private String stagedOccurrenceBindingSetIdentity;
        private List<ClosureProcessResult.GraphChange> stagedGraphChanges;
        private String stagedGraphChangesIdentity;
        private List<ClosureProcessResult.CheckpointWrite> stagedCheckpointWrites;
        private String stagedCheckpointWritesIdentity;
        private List<ClosureProcessResult.SubscriptionDelta> stagedSubscriptionDeltas;
        private String stagedSubscriptionDeltasIdentity;
        private List<PublicEventOccurrence> stagedEvents;
        private String stagedPublicEventsIdentity;
        private ClosureCommitReceipt stagedReceipt;
        private ClosureCommitPlan verifiedPlan;
        private boolean documentsStaged;
        private boolean componentsStaged;
        private boolean occurrencesStaged;
        private boolean graphChangesStaged;
        private boolean checkpointWritesStaged;
        private boolean subscriptionDeltasStaged;
        private boolean publicEventsStaged;
        private boolean committed;

        @Override
        public void verifyExpectedState(ClosureCommitPlan plan) {
            if (verifiedPlan != null) {
                throw new IllegalStateException("expected state already verified");
            }
            ClosureCommitPlan exactPlan = Objects.requireNonNull(plan, "plan");
            requireExpectedState(
                    exactPlan,
                    baseClosureIdentity,
                    baseGraphGeneration,
                    baseDocuments,
                    baseComponents,
                    baseOccurrences,
                    baseOccurrenceBindingSetIdentity);
            verifiedPlan = exactPlan;
        }

        @Override
        public void stageDocuments(List<ResultingDocument> values) {
            requireNotStaged(documentsStaged, "documents");
            for (ResultingDocument value : Objects.requireNonNull(values, "documents")) {
                ResultingDocument exact = Objects.requireNonNull(value, "document");
                stagedDocuments.put(exact.documentId(), exact);
            }
            documentsStaged = true;
        }

        @Override
        public void stageComponents(List<ResultingComponent> values) {
            requireNotStaged(componentsStaged, "components");
            stagedComponents = copy(values, "components");
            componentsStaged = true;
        }

        @Override
        public void stageOccurrences(
                List<ManagedOccurrenceBinding> values,
                String identity) {
            requireNotStaged(occurrencesStaged, "occurrences");
            ArrayList<ManagedOccurrenceBinding> exactOccurrences =
                    copy(values, "occurrences");
            String recomputedIdentity = recomputeOccurrenceBindingSetIdentity(
                    exactOccurrences);
            if (!recomputedIdentity.equals(Objects.requireNonNull(
                    identity, "occurrenceBindingSetIdentity"))) {
                throw new IllegalArgumentException(
                        "occurrenceBindingSetIdentity mismatch");
            }
            stagedOccurrences = exactOccurrences;
            stagedOccurrenceBindingSetIdentity = recomputedIdentity;
            occurrencesStaged = true;
        }

        @Override
        public void stageGraphChanges(
                List<ClosureProcessResult.GraphChange> values,
                String identity) {
            requireNotStaged(graphChangesStaged, "graphChanges");
            stagedGraphChanges = copy(values, "graphChanges");
            stagedGraphChangesIdentity = Objects.requireNonNull(
                    identity, "graphChangesIdentity");
            graphChangesStaged = true;
        }

        @Override
        public void stageCheckpointWrites(
                List<ClosureProcessResult.CheckpointWrite> values,
                String identity) {
            requireNotStaged(checkpointWritesStaged, "checkpointWrites");
            stagedCheckpointWrites = copy(values, "checkpointWrites");
            stagedCheckpointWritesIdentity = Objects.requireNonNull(
                    identity, "checkpointWritesIdentity");
            checkpointWritesStaged = true;
        }

        @Override
        public void stageSubscriptionDeltas(
                List<ClosureProcessResult.SubscriptionDelta> values,
                String identity) {
            requireNotStaged(subscriptionDeltasStaged, "subscriptionDeltas");
            stagedSubscriptionDeltas = copy(values, "subscriptionDeltas");
            stagedSubscriptionDeltasIdentity = Objects.requireNonNull(
                    identity, "subscriptionDeltasIdentity");
            subscriptionDeltasStaged = true;
        }

        @Override
        public void stagePublicEvents(
                List<PublicEventOccurrence> values,
                String identity) {
            requireNotStaged(publicEventsStaged, "publicEvents");
            stagedEvents = new ArrayList<PublicEventOccurrence>(events);
            stagedEvents.addAll(copy(values, "publicEvents"));
            stagedPublicEventsIdentity = Objects.requireNonNull(
                    identity, "publicEventsIdentity");
            publicEventsStaged = true;
        }

        @Override
        public void stageReceipt(ClosureCommitReceipt value) {
            if (stagedReceipt != null) {
                throw new IllegalStateException("receipt already staged");
            }
            stagedReceipt = Objects.requireNonNull(value, "receipt");
        }

        @Override
        public void commit() {
            synchronized (InMemoryClosureStore.this) {
                if (committed) {
                    throw new IllegalStateException("transaction already committed");
                }
                requireCompleteStaging();
                if (storeVersion != baseStoreVersion) {
                    throw new IllegalStateException("closure state changed after begin");
                }
                requireExpectedState(
                        verifiedPlan,
                        closureIdentity,
                        graphGeneration,
                        documents,
                        components,
                        occurrences,
                        occurrenceBindingSetIdentity);
                String recomputedOutputClosureIdentity =
                        requireStagedOutputMatchesPlan();

                closureIdentity = recomputedOutputClosureIdentity;
                graphGeneration = verifiedPlan.outputGraphGeneration();
                documents = copyDocuments(stagedDocuments);
                components = new ArrayList<ResultingComponent>(stagedComponents);
                occurrences = new ArrayList<ManagedOccurrenceBinding>(stagedOccurrences);
                occurrenceBindingSetIdentity = stagedOccurrenceBindingSetIdentity;
                graphChanges = new ArrayList<ClosureProcessResult.GraphChange>(
                        stagedGraphChanges);
                graphChangesIdentity = stagedGraphChangesIdentity;
                checkpointWrites = new ArrayList<ClosureProcessResult.CheckpointWrite>(
                        stagedCheckpointWrites);
                checkpointWritesIdentity = stagedCheckpointWritesIdentity;
                subscriptionDeltas = new ArrayList<ClosureProcessResult.SubscriptionDelta>(
                        stagedSubscriptionDeltas);
                subscriptionDeltasIdentity = stagedSubscriptionDeltasIdentity;
                events = new ArrayList<PublicEventOccurrence>(stagedEvents);
                publicEventsIdentity = stagedPublicEventsIdentity;
                receipt = stagedReceipt;
                storeVersion = CanonicalOrders.requireSafeInteger(
                        Math.addExact(storeVersion, 1L), "storeVersion");
                committed = true;
            }
        }

        private void requireCompleteStaging() {
            if (verifiedPlan == null
                    || !documentsStaged
                    || !componentsStaged
                    || !occurrencesStaged
                    || !graphChangesStaged
                    || !checkpointWritesStaged
                    || !subscriptionDeltasStaged
                    || !publicEventsStaged
                    || stagedReceipt == null) {
                throw new IllegalStateException("incomplete closure staging");
            }
        }

        private String requireStagedOutputMatchesPlan() {
            requireIdentity(
                    recomputeOccurrenceBindingSetIdentity(stagedOccurrences),
                    stagedOccurrenceBindingSetIdentity,
                    "recomputed occurrenceBindingSetIdentity");
            requireIdentity(
                    stagedOccurrenceBindingSetIdentity,
                    verifiedPlan.occurrenceBindingSetIdentity(),
                    "occurrenceBindingSetIdentity");
            requireIdentity(
                    stagedGraphChangesIdentity,
                    verifiedPlan.graphChangesIdentity(),
                    "graphChangesIdentity");
            requireIdentity(
                    stagedCheckpointWritesIdentity,
                    verifiedPlan.checkpointWritesIdentity(),
                    "checkpointWritesIdentity");
            requireIdentity(
                    stagedSubscriptionDeltasIdentity,
                    verifiedPlan.subscriptionDeltasIdentity(),
                    "subscriptionDeltasIdentity");
            requireIdentity(
                    stagedPublicEventsIdentity,
                    verifiedPlan.publicEventsIdentity(),
                    "publicEventsIdentity");
            requireIdentity(
                    stagedReceipt.contractsCommitCompanionIdentity(),
                    verifiedPlan.companionIdentity(),
                    "companionIdentity");
            String recomputedOutputClosureIdentity = recomputeClosureIdentity(
                    verifiedPlan.outputGraphGeneration(),
                    stagedDocuments,
                    stagedComponents,
                    stagedOccurrenceBindingSetIdentity);
            requireIdentity(
                    recomputedOutputClosureIdentity,
                    verifiedPlan.outputClosureIdentity(),
                    "outputClosureIdentity");

            for (ClosureCommitPlan.ResultingDocumentIdentity expected
                    : verifiedPlan.resultingDocuments()) {
                ResultingDocument before = baseDocuments.get(expected.documentId());
                ResultingDocument after = stagedDocuments.get(expected.documentId());
                if (before == null
                        || after == null
                        || !expected.beforeBlueId().equals(before.afterBlueId())
                        || !expected.afterBlueId().equals(after.afterBlueId())) {
                    throw new IllegalStateException(
                            "staged document mismatch for " + expected.documentId());
                }
            }

            List<ClosureCommitPlan.ResultingComponentIdentity> expectedComponents =
                    verifiedPlan.resultingComponents();
            if (expectedComponents.size() != stagedComponents.size()) {
                throw new IllegalStateException("staged component count");
            }
            for (int index = 0; index < expectedComponents.size(); index++) {
                ClosureCommitPlan.ResultingComponentIdentity expected =
                        expectedComponents.get(index);
                ResultingComponent actual = stagedComponents.get(index);
                if (!expected.componentIdentity().equals(actual.componentIdentity())
                        || !expected.componentStateIdentity().equals(
                                actual.componentStateIdentity())
                        || !Objects.equals(
                                expected.cyclicProofIdentity(),
                                actual.cyclicProofIdentity())) {
                    throw new IllegalStateException("staged component mismatch");
                }
            }
            return recomputedOutputClosureIdentity;
        }
    }

    private void requireExpectedState(
            ClosureCommitPlan plan,
            String actualClosureIdentity,
            long actualGraphGeneration,
            Map<DocumentId, ResultingDocument> actualDocuments,
            List<ResultingComponent> actualComponents,
            List<ManagedOccurrenceBinding> actualOccurrences,
            String actualOccurrenceBindingSetIdentity) {
        requireIdentity(
                recomputeOccurrenceBindingSetIdentity(actualOccurrences),
                actualOccurrenceBindingSetIdentity,
                "stored occurrenceBindingSetIdentity");
        String recomputedClosureIdentity = recomputeClosureIdentity(
                actualGraphGeneration,
                actualDocuments,
                actualComponents,
                actualOccurrenceBindingSetIdentity);
        requireIdentity(
                recomputedClosureIdentity,
                actualClosureIdentity,
                "stored closureIdentity");
        requireIdentity(
                plan.inputClosureIdentity(),
                recomputedClosureIdentity,
                "inputClosureIdentity");
        if (plan.expectedInputGraphGeneration() != actualGraphGeneration) {
            throw new IllegalStateException("graph generation conflict");
        }
        if (plan.expectedInputDocuments().size() != actualDocuments.size()) {
            throw new IllegalStateException("input document count conflict");
        }
        for (ClosureCommitPlan.InputDocumentExpectation expected
                : plan.expectedInputDocuments()) {
            ResultingDocument actual = actualDocuments.get(expected.documentId());
            if (actual == null || !expected.blueId().equals(actual.afterBlueId())) {
                throw new IllegalStateException(
                        "head conflict for " + expected.documentId());
            }
        }
        if (plan.expectedInputComponents().size() != actualComponents.size()) {
            throw new IllegalStateException("component count conflict");
        }
        for (int index = 0; index < actualComponents.size(); index++) {
            ClosureCommitPlan.InputComponentExpectation expected =
                    plan.expectedInputComponents().get(index);
            ResultingComponent actual = actualComponents.get(index);
            if (!expected.componentIdentity().equals(actual.componentIdentity())
                    || !expected.componentStateIdentity().equals(
                            actual.componentStateIdentity())
                    || expected.componentGeneration() != actual.componentGeneration()
                    || !Objects.equals(expected.masterBlueId(), actual.masterBlueId())) {
                throw new IllegalStateException("component state conflict");
            }
        }
        requireIdentity(
                plan.inputOccurrenceBindingSetIdentity(),
                actualOccurrenceBindingSetIdentity,
                "inputOccurrenceBindingSetIdentity");
    }

    private String recomputeOccurrenceBindingSetIdentity(
            List<ManagedOccurrenceBinding> values) {
        return Objects.requireNonNull(
                bindingSetIdentityFactory.identity(
                        Collections.unmodifiableList(
                                new ArrayList<ManagedOccurrenceBinding>(values))),
                "recomputed occurrenceBindingSetIdentity");
    }

    private String recomputeClosureIdentity(
            long exactGraphGeneration,
            Map<DocumentId, ResultingDocument> exactDocuments,
            List<ResultingComponent> exactComponents,
            String exactOccurrenceBindingSetIdentity) {
        TreeMap<DocumentId, AffectedClosureSnapshot.ManagedDocument> sortedDocuments =
                new TreeMap<DocumentId, AffectedClosureSnapshot.ManagedDocument>(
                        CanonicalOrders.DOCUMENT_ID);
        ArrayList<DocumentId> publicRootDocumentIds = new ArrayList<DocumentId>();
        for (Map.Entry<DocumentId, ResultingDocument> entry
                : Objects.requireNonNull(
                        exactDocuments, "exactDocuments").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "exact document key");
            ResultingDocument document = Objects.requireNonNull(
                    entry.getValue(), "exact document value");
            if (!documentId.equals(document.documentId())) {
                throw new IllegalStateException("exact document key mismatch");
            }
            sortedDocuments.put(
                    documentId,
                    new AffectedClosureSnapshot.ManagedDocument(
                            documentId,
                            document.afterBlueId(),
                            document.document(),
                            document.initialized(),
                            document.terminated(),
                            document.publicRoot(),
                            document.epoch(),
                            document.componentGeneration()));
            if (document.publicRoot()) {
                publicRootDocumentIds.add(documentId);
            }
        }
        ArrayList<ComponentSnapshot> componentSnapshots =
                new ArrayList<ComponentSnapshot>();
        for (ResultingComponent component
                : Objects.requireNonNull(
                        exactComponents, "exactComponents")) {
            ResultingComponent exact = Objects.requireNonNull(
                    component, "exact component");
            componentSnapshots.add(new ComponentSnapshot(
                    exact.componentIdentity(),
                    exact.componentStateIdentity(),
                    exact.componentGeneration(),
                    exact.kind(),
                    exact.orderedMemberDocumentIds(),
                    exact.orderedMemberBlueIds(),
                    exact.masterBlueId(),
                    exact.completeCyclicProof(),
                    exact.cyclicProofIdentity()));
        }
        return Objects.requireNonNull(
                closureIdentityFactory.identity(
                        CanonicalOrders.requireSafeInteger(
                                exactGraphGeneration, "exactGraphGeneration"),
                        Collections.unmodifiableMap(
                                new LinkedHashMap<DocumentId,
                                        AffectedClosureSnapshot.ManagedDocument>(
                                                sortedDocuments)),
                        Objects.requireNonNull(
                                exactOccurrenceBindingSetIdentity,
                                "exactOccurrenceBindingSetIdentity"),
                        Collections.unmodifiableList(componentSnapshots),
                        Collections.unmodifiableList(publicRootDocumentIds)),
                "recomputed closureIdentity");
    }

    private static void requireIdentity(String actual, String expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException(field + " conflict");
        }
    }

    private static void requireNotStaged(boolean staged, String field) {
        if (staged) {
            throw new IllegalStateException(field + " already staged");
        }
    }

    private static <T> ArrayList<T> copy(List<T> values, String field) {
        ArrayList<T> result = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T value : result) {
            Objects.requireNonNull(value, field + " item");
        }
        return result;
    }

    private static Map<DocumentId, ResultingDocument> copyDocuments(
            Map<DocumentId, ResultingDocument> values) {
        TreeMap<DocumentId, ResultingDocument> sorted =
                new TreeMap<DocumentId, ResultingDocument>(
                        CanonicalOrders.DOCUMENT_ID);
        for (Map.Entry<DocumentId, ResultingDocument> entry
                : Objects.requireNonNull(values, "documents").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "document key");
            ResultingDocument document = Objects.requireNonNull(
                    entry.getValue(), "document value");
            if (!documentId.equals(document.documentId())) {
                throw new IllegalArgumentException("document key mismatch");
            }
            sorted.put(documentId, document);
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("documents");
        }
        return new LinkedHashMap<DocumentId, ResultingDocument>(sorted);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
