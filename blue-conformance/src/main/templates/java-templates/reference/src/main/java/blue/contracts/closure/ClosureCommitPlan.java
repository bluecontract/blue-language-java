package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact platform commit companion and compare-and-swap expectations. */
public final class ClosureCommitPlan {
    private final String companionIdentity;
    private final String invocationIdentity;
    private final String inputClosureIdentity;
    private final String outputClosureIdentity;
    private final long expectedInputGraphGeneration;
    private final List<InputDocumentExpectation> expectedInputDocuments;
    private final List<InputComponentExpectation> expectedInputComponents;
    private final String inputOccurrenceBindingSetIdentity;
    private final long outputGraphGeneration;
    private final List<ResultingDocumentIdentity> resultingDocuments;
    private final List<ResultingComponentIdentity> resultingComponents;
    private final String occurrenceBindingSetIdentity;
    private final String graphChangesIdentity;
    private final String checkpointWritesIdentity;
    private final String subscriptionDeltasIdentity;
    private final String publicEventsIdentity;
    private final String gasTraceIdentity;
    private final AffectedClosureSnapshot.EnvironmentEvidence environment;

    public ClosureCommitPlan(
            String companionIdentity,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long expectedInputGraphGeneration,
            List<InputDocumentExpectation> expectedInputDocuments,
            List<InputComponentExpectation> expectedInputComponents,
            String inputOccurrenceBindingSetIdentity,
            long outputGraphGeneration,
            List<ResultingDocumentIdentity> resultingDocuments,
            List<ResultingComponentIdentity> resultingComponents,
            String occurrenceBindingSetIdentity,
            String graphChangesIdentity,
            String checkpointWritesIdentity,
            String subscriptionDeltasIdentity,
            String publicEventsIdentity,
            String gasTraceIdentity,
            AffectedClosureSnapshot.EnvironmentEvidence environment) {
        this.companionIdentity = Objects.requireNonNull(
                companionIdentity, "companionIdentity");
        this.invocationIdentity = Objects.requireNonNull(invocationIdentity, "invocationIdentity");
        this.inputClosureIdentity = Objects.requireNonNull(
                inputClosureIdentity, "inputClosureIdentity");
        this.outputClosureIdentity = Objects.requireNonNull(
                outputClosureIdentity, "outputClosureIdentity");
        this.expectedInputGraphGeneration = CanonicalOrders.requireSafeInteger(
                expectedInputGraphGeneration, "expectedInputGraphGeneration");
        this.expectedInputDocuments = immutableList(
                expectedInputDocuments, "expectedInputDocuments");
        this.expectedInputComponents = immutableList(
                expectedInputComponents, "expectedInputComponents");
        this.inputOccurrenceBindingSetIdentity = Objects.requireNonNull(
                inputOccurrenceBindingSetIdentity, "inputOccurrenceBindingSetIdentity");
        this.outputGraphGeneration = CanonicalOrders.requireSafeInteger(
                outputGraphGeneration, "outputGraphGeneration");
        this.resultingDocuments = immutableList(resultingDocuments, "resultingDocuments");
        this.resultingComponents = immutableList(resultingComponents, "resultingComponents");
        this.occurrenceBindingSetIdentity = Objects.requireNonNull(
                occurrenceBindingSetIdentity, "occurrenceBindingSetIdentity");
        this.graphChangesIdentity = Objects.requireNonNull(
                graphChangesIdentity, "graphChangesIdentity");
        this.checkpointWritesIdentity = Objects.requireNonNull(
                checkpointWritesIdentity, "checkpointWritesIdentity");
        this.subscriptionDeltasIdentity = Objects.requireNonNull(
                subscriptionDeltasIdentity, "subscriptionDeltasIdentity");
        this.publicEventsIdentity = Objects.requireNonNull(
                publicEventsIdentity, "publicEventsIdentity");
        this.gasTraceIdentity = Objects.requireNonNull(gasTraceIdentity, "gasTraceIdentity");
        this.environment = Objects.requireNonNull(environment, "environment");
        requireInputDocumentOrder(this.expectedInputDocuments);
        requireResultDocumentOrder(this.resultingDocuments);
        requireDistinctInputComponents(this.expectedInputComponents);
        requireDistinctResultComponents(this.resultingComponents);
    }

    private static <T> List<T> immutableList(List<T> values, String field) {
        ArrayList<T> copy = new ArrayList<T>(Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireInputDocumentOrder(
            List<InputDocumentExpectation> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("expectedInputDocuments");
        }
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).documentId().compareTo(
                    values.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "expectedInputDocuments not in canonical order");
            }
        }
    }

    private static void requireResultDocumentOrder(
            List<ResultingDocumentIdentity> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("resultingDocuments");
        }
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).documentId().compareTo(
                    values.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "resultingDocuments not in canonical order");
            }
        }
    }

    private static void requireDistinctInputComponents(
            List<InputComponentExpectation> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("expectedInputComponents");
        }
        Set<String> identities = new HashSet<String>();
        for (InputComponentExpectation value : values) {
            if (!identities.add(value.componentIdentity())) {
                throw new IllegalArgumentException(
                        "duplicate expected componentIdentity");
            }
        }
    }

    private static void requireDistinctResultComponents(
            List<ResultingComponentIdentity> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("resultingComponents");
        }
        Set<String> identities = new HashSet<String>();
        for (ResultingComponentIdentity value : values) {
            if (!identities.add(value.componentIdentity())) {
                throw new IllegalArgumentException(
                        "duplicate resulting componentIdentity");
            }
        }
    }

    public String companionIdentity() { return companionIdentity; }
    public String invocationIdentity() { return invocationIdentity; }
    public String inputClosureIdentity() { return inputClosureIdentity; }
    public String outputClosureIdentity() { return outputClosureIdentity; }
    public long expectedInputGraphGeneration() { return expectedInputGraphGeneration; }
    public List<InputDocumentExpectation> expectedInputDocuments() { return expectedInputDocuments; }
    public List<InputComponentExpectation> expectedInputComponents() { return expectedInputComponents; }
    public String inputOccurrenceBindingSetIdentity() { return inputOccurrenceBindingSetIdentity; }
    public long outputGraphGeneration() { return outputGraphGeneration; }
    public List<ResultingDocumentIdentity> resultingDocuments() { return resultingDocuments; }
    public List<ResultingComponentIdentity> resultingComponents() { return resultingComponents; }
    public String occurrenceBindingSetIdentity() { return occurrenceBindingSetIdentity; }
    public String graphChangesIdentity() { return graphChangesIdentity; }
    public String checkpointWritesIdentity() { return checkpointWritesIdentity; }
    public String subscriptionDeltasIdentity() { return subscriptionDeltasIdentity; }
    public String publicEventsIdentity() { return publicEventsIdentity; }
    public String gasTraceIdentity() { return gasTraceIdentity; }
    public String blueLanguageSpecificationIdentity() {
        return environment.blueLanguageSpecificationIdentity();
    }
    public String contractsSpecificationIdentity() {
        return environment.contractsSpecificationIdentity();
    }
    public String managedDocumentIdentityPolicyIdentity() {
        return environment.managedDocumentIdentityPolicy().identity();
    }
    public String managedBindingPolicyIdentity() {
        return environment.managedBindingPolicy().identity();
    }
    public String exactNodeProviderDomainIdentity() {
        return environment.exactNodeProviderDomain().identity();
    }
    public String externalOrderPolicyIdentity() {
        return environment.externalOrderPolicy().identity();
    }
    public String runtimeRegistryIdentity() { return environment.runtimeRegistryIdentity(); }
    public String gasManifestIdentity() { return environment.gasManifestIdentity(); }
    public String portableLimitPolicyIdentity() {
        return environment.portableLimitPolicy().identity();
    }
    public String cyclicFinalizerIdentity() { return environment.cyclicFinalizerIdentity(); }
    public String cyclicProofVerifierIdentity() {
        return environment.cyclicProofVerifierIdentity();
    }

    public static final class InputDocumentExpectation {
        private final DocumentId documentId;
        private final String blueId;

        public InputDocumentExpectation(DocumentId documentId, String blueId) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.blueId = Objects.requireNonNull(blueId, "blueId");
        }

        public DocumentId documentId() { return documentId; }
        public String blueId() { return blueId; }
    }

    public static final class InputComponentExpectation {
        private final String componentIdentity;
        private final String componentStateIdentity;
        private final long componentGeneration;
        private final String masterBlueId;

        public InputComponentExpectation(
                String componentIdentity,
                String componentStateIdentity,
                long componentGeneration,
                String masterBlueId) {
            this.componentIdentity = Objects.requireNonNull(
                    componentIdentity, "componentIdentity");
            this.componentStateIdentity = Objects.requireNonNull(
                    componentStateIdentity, "componentStateIdentity");
            this.componentGeneration = CanonicalOrders.requireSafeInteger(
                    componentGeneration, "componentGeneration");
            this.masterBlueId = masterBlueId;
        }

        public String componentIdentity() { return componentIdentity; }
        public String componentStateIdentity() { return componentStateIdentity; }
        public long componentGeneration() { return componentGeneration; }
        public String masterBlueId() { return masterBlueId; }
    }

    public static final class ResultingDocumentIdentity {
        private final DocumentId documentId;
        private final String beforeBlueId;
        private final String afterBlueId;

        public ResultingDocumentIdentity(
                DocumentId documentId, String beforeBlueId, String afterBlueId) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.beforeBlueId = Objects.requireNonNull(beforeBlueId, "beforeBlueId");
            this.afterBlueId = Objects.requireNonNull(afterBlueId, "afterBlueId");
        }

        public DocumentId documentId() { return documentId; }
        public String beforeBlueId() { return beforeBlueId; }
        public String afterBlueId() { return afterBlueId; }
    }

    public static final class ResultingComponentIdentity {
        private final String componentIdentity;
        private final String componentStateIdentity;
        private final String cyclicProofIdentity;

        public ResultingComponentIdentity(
                String componentIdentity,
                String componentStateIdentity,
                String cyclicProofIdentity) {
            this.componentIdentity = Objects.requireNonNull(
                    componentIdentity, "componentIdentity");
            this.componentStateIdentity = Objects.requireNonNull(
                    componentStateIdentity, "componentStateIdentity");
            this.cyclicProofIdentity = cyclicProofIdentity;
        }

        public String componentIdentity() { return componentIdentity; }
        public String componentStateIdentity() { return componentStateIdentity; }
        public String cyclicProofIdentity() { return cyclicProofIdentity; }
    }
}
