package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact non-semantic compare-and-swap companion for atomic closure publication.
 *
 * <p>All 27 fields in the normative companion constructor are exposed
 * directly; {@code companionIdentity} is the independently verified 28th
 * serialized field.</p>
 */
public final class ClosureCommitCompanion {

    private final String companionIdentity;
    private final String invocationIdentity;
    private final String inputClosureIdentity;
    private final String outputClosureIdentity;
    private final long expectedInputGraphGeneration;
    private final List<InputDocument> expectedInputDocuments;
    private final List<InputComponent> expectedInputComponents;
    private final String inputOccurrenceBindingSetIdentity;
    private final long outputGraphGeneration;
    private final List<DocumentDelta> resultingDocuments;
    private final List<ResultComponent> resultingComponents;
    private final String occurrenceBindingSetIdentity;
    private final String graphChangesIdentity;
    private final String checkpointWritesIdentity;
    private final String subscriptionDeltasIdentity;
    private final String publicEventsIdentity;
    private final String gasTraceIdentity;
    private final ClosureEnvironment environment;

    /**
     * Creates and independently verifies a complete companion.
     *
     * @param companionIdentity asserted exact companion identity
     * @param invocationIdentity exact invocation identity
     * @param inputClosureIdentity expected input closure identity
     * @param outputClosureIdentity staged output closure identity
     * @param expectedInputGraphGeneration compare-and-swap graph generation
     * @param expectedInputDocuments canonical input document expectations
     * @param expectedInputComponents canonical input component expectations
     * @param inputOccurrenceBindingSetIdentity input row-set expectation
     * @param outputGraphGeneration staged output graph generation
     * @param resultingDocuments canonical changed document identities
     * @param resultingComponents complete staged component identities
     * @param occurrenceBindingSetIdentity staged complete row-set identity
     * @param graphChangesIdentity exact graph-change sequence identity
     * @param checkpointWritesIdentity exact checkpoint sequence identity
     * @param subscriptionDeltasIdentity exact subscription sequence identity
     * @param publicEventsIdentity exact public-event sequence identity
     * @param gasTraceIdentity exact admitted gas-trace identity
     * @param environment complete environment identity snapshot
     */
    public ClosureCommitCompanion(
            String companionIdentity,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long expectedInputGraphGeneration,
            List<InputDocument> expectedInputDocuments,
            List<InputComponent> expectedInputComponents,
            String inputOccurrenceBindingSetIdentity,
            long outputGraphGeneration,
            List<DocumentDelta> resultingDocuments,
            List<ResultComponent> resultingComponents,
            String occurrenceBindingSetIdentity,
            String graphChangesIdentity,
            String checkpointWritesIdentity,
            String subscriptionDeltasIdentity,
            String publicEventsIdentity,
            String gasTraceIdentity,
            ClosureEnvironment environment) {
        this.invocationIdentity = identity(
                invocationIdentity, "invocationIdentity");
        this.inputClosureIdentity = identity(
                inputClosureIdentity, "inputClosureIdentity");
        this.outputClosureIdentity = identity(
                outputClosureIdentity, "outputClosureIdentity");
        this.expectedInputGraphGeneration =
                ClosureValueSupport.requireSafeInteger(
                        expectedInputGraphGeneration,
                        "expectedInputGraphGeneration");
        this.expectedInputDocuments = canonicalInputDocuments(
                expectedInputDocuments);
        this.expectedInputComponents = distinctInputComponents(
                expectedInputComponents);
        this.inputOccurrenceBindingSetIdentity = identity(
                inputOccurrenceBindingSetIdentity,
                "inputOccurrenceBindingSetIdentity");
        this.outputGraphGeneration = ClosureValueSupport.requireSafeInteger(
                outputGraphGeneration, "outputGraphGeneration");
        this.resultingDocuments = canonicalDocumentDeltas(
                resultingDocuments);
        this.resultingComponents = distinctResultComponents(
                resultingComponents);
        this.occurrenceBindingSetIdentity = identity(
                occurrenceBindingSetIdentity,
                "occurrenceBindingSetIdentity");
        this.graphChangesIdentity = identity(
                graphChangesIdentity, "graphChangesIdentity");
        this.checkpointWritesIdentity = identity(
                checkpointWritesIdentity, "checkpointWritesIdentity");
        this.subscriptionDeltasIdentity = identity(
                subscriptionDeltasIdentity, "subscriptionDeltasIdentity");
        this.publicEventsIdentity = identity(
                publicEventsIdentity, "publicEventsIdentity");
        this.gasTraceIdentity = identity(
                gasTraceIdentity, "gasTraceIdentity");
        this.environment = Objects.requireNonNull(environment, "environment");
        String asserted = identity(companionIdentity, "companionIdentity");
        String computed = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.PLATFORM_COMMIT_COMPANION,
                identityConstructorValue());
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    "companionIdentity does not identify this companion");
        }
        this.companionIdentity = asserted;
    }

    /**
     * Returns exact companion identity.
     *
     * @return exact companion identity
     */
    public String companionIdentity() {
        return companionIdentity;
    }

    /**
     * Returns exact invocation identity.
     *
     * @return exact invocation identity
     */
    public String invocationIdentity() {
        return invocationIdentity;
    }

    /**
     * Returns expected input closure identity.
     *
     * @return expected input closure identity
     */
    public String inputClosureIdentity() {
        return inputClosureIdentity;
    }

    /**
     * Returns staged output closure identity.
     *
     * @return staged output closure identity
     */
    public String outputClosureIdentity() {
        return outputClosureIdentity;
    }

    /**
     * Returns expected input graph generation.
     *
     * @return expected input graph generation
     */
    public long expectedInputGraphGeneration() {
        return expectedInputGraphGeneration;
    }

    /**
     * Returns immutable canonical input document expectations.
     *
     * @return immutable canonical input document expectations
     */
    public List<InputDocument> expectedInputDocuments() {
        return expectedInputDocuments;
    }

    /**
     * Returns immutable canonical input component expectations.
     *
     * @return immutable canonical input component expectations
     */
    public List<InputComponent> expectedInputComponents() {
        return expectedInputComponents;
    }

    /**
     * Returns exact input occurrence-row-set identity.
     *
     * @return exact input occurrence-row-set identity
     */
    public String inputOccurrenceBindingSetIdentity() {
        return inputOccurrenceBindingSetIdentity;
    }

    /**
     * Returns staged output graph generation.
     *
     * @return staged output graph generation
     */
    public long outputGraphGeneration() {
        return outputGraphGeneration;
    }

    /**
     * Returns immutable canonical changed document identities.
     *
     * @return immutable canonical changed document identities
     */
    public List<DocumentDelta> resultingDocuments() {
        return resultingDocuments;
    }

    /**
     * Returns immutable complete staged component identities.
     *
     * @return immutable complete staged component identities
     */
    public List<ResultComponent> resultingComponents() {
        return resultingComponents;
    }

    /**
     * Returns exact staged occurrence-row-set identity.
     *
     * @return exact staged occurrence-row-set identity
     */
    public String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    /**
     * Compatibility alias for the exact staged row-set identity.
     *
     * @return exact staged occurrence-row-set identity
     */
    public String outputOccurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    /**
     * Returns exact graph-change sequence identity.
     *
     * @return exact graph-change sequence identity
     */
    public String graphChangesIdentity() {
        return graphChangesIdentity;
    }

    /**
     * Returns exact checkpoint-write sequence identity.
     *
     * @return exact checkpoint-write sequence identity
     */
    public String checkpointWritesIdentity() {
        return checkpointWritesIdentity;
    }

    /**
     * Returns exact subscription-delta sequence identity.
     *
     * @return exact subscription-delta sequence identity
     */
    public String subscriptionDeltasIdentity() {
        return subscriptionDeltasIdentity;
    }

    /**
     * Returns exact public-event sequence identity.
     *
     * @return exact public-event sequence identity
     */
    public String publicEventsIdentity() {
        return publicEventsIdentity;
    }

    /**
     * Returns exact admitted gas-trace identity.
     *
     * @return exact admitted gas-trace identity
     */
    public String gasTraceIdentity() {
        return gasTraceIdentity;
    }

    /**
     * Returns selected Language specification identity.
     *
     * @return selected Language specification identity
     */
    public String blueLanguageSpecificationIdentity() {
        return environment.blueLanguageSpecificationIdentity();
    }

    /**
     * Returns selected Contracts specification identity.
     *
     * @return selected Contracts specification identity
     */
    public String contractsSpecificationIdentity() {
        return environment.contractsSpecificationIdentity();
    }

    /**
     * Returns managed-document policy identity.
     *
     * @return managed-document policy identity
     */
    public String managedDocumentIdentityPolicyIdentity() {
        return environment.managedDocumentIdentityPolicyIdentity();
    }

    /**
     * Returns managed-binding policy identity.
     *
     * @return managed-binding policy identity
     */
    public String managedBindingPolicyIdentity() {
        return environment.managedBindingPolicyIdentity();
    }

    /**
     * Returns exact-node provider-domain identity.
     *
     * @return exact-node provider-domain identity
     */
    public String exactNodeProviderDomainIdentity() {
        return environment.exactNodeProviderDomainIdentity();
    }

    /**
     * Returns external-order policy identity.
     *
     * @return external-order policy identity
     */
    public String externalOrderPolicyIdentity() {
        return environment.externalOrderPolicyIdentity();
    }

    /**
     * Returns frozen runtime registry identity.
     *
     * @return frozen runtime registry identity
     */
    public String runtimeRegistryIdentity() {
        return environment.runtimeRegistryIdentity();
    }

    /**
     * Returns selected gas-manifest identity.
     *
     * @return selected gas-manifest identity
     */
    public String gasManifestIdentity() {
        return environment.gasManifestIdentity();
    }

    /**
     * Returns selected portable-limit policy identity.
     *
     * @return selected portable-limit policy identity
     */
    public String portableLimitPolicyIdentity() {
        return environment.portableLimitPolicyIdentity();
    }

    /**
     * Returns selected Language cyclic-finalizer identity.
     *
     * @return selected Language cyclic-finalizer identity
     */
    public String cyclicFinalizerIdentity() {
        return environment.cyclicFinalizerIdentity();
    }

    /**
     * Returns selected cyclic-proof-verifier identity.
     *
     * @return selected cyclic-proof-verifier identity
     */
    public String cyclicProofVerifierIdentity() {
        return environment.cyclicProofVerifierIdentity();
    }

    Map<String, Object> identityConstructorValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("invocationIdentity", invocationIdentity);
        value.put("inputClosureIdentity", inputClosureIdentity);
        value.put("outputClosureIdentity", outputClosureIdentity);
        value.put("expectedInputGraphGeneration",
                Long.valueOf(expectedInputGraphGeneration));
        value.put("expectedInputDocuments",
                inputDocumentValues(expectedInputDocuments));
        value.put("expectedInputComponents",
                inputComponentValues(expectedInputComponents));
        value.put("inputOccurrenceBindingSetIdentity",
                inputOccurrenceBindingSetIdentity);
        value.put("outputGraphGeneration",
                Long.valueOf(outputGraphGeneration));
        value.put("resultingDocuments",
                documentDeltaValues(resultingDocuments));
        value.put("resultingComponents",
                resultComponentValues(resultingComponents));
        value.put("occurrenceBindingSetIdentity",
                occurrenceBindingSetIdentity);
        value.put("graphChangesIdentity", graphChangesIdentity);
        value.put("checkpointWritesIdentity", checkpointWritesIdentity);
        value.put("subscriptionDeltasIdentity", subscriptionDeltasIdentity);
        value.put("publicEventsIdentity", publicEventsIdentity);
        value.put("gasTraceIdentity", gasTraceIdentity);
        value.put("blueLanguageSpecificationIdentity",
                blueLanguageSpecificationIdentity());
        value.put("contractsSpecificationIdentity",
                contractsSpecificationIdentity());
        value.put("managedDocumentIdentityPolicyIdentity",
                managedDocumentIdentityPolicyIdentity());
        value.put("managedBindingPolicyIdentity",
                managedBindingPolicyIdentity());
        value.put("exactNodeProviderDomainIdentity",
                exactNodeProviderDomainIdentity());
        value.put("externalOrderPolicyIdentity",
                externalOrderPolicyIdentity());
        value.put("runtimeRegistryIdentity", runtimeRegistryIdentity());
        value.put("gasManifestIdentity", gasManifestIdentity());
        value.put("portableLimitPolicyIdentity",
                portableLimitPolicyIdentity());
        value.put("cyclicFinalizerIdentity", cyclicFinalizerIdentity());
        value.put("cyclicProofVerifierIdentity",
                cyclicProofVerifierIdentity());
        return value;
    }

    private static String identity(String value, String field) {
        return ClosureValueSupport.requireSha256Identity(value, field);
    }

    private static List<InputDocument> canonicalInputDocuments(
            List<InputDocument> values) {
        ArrayList<InputDocument> copy = copy(values, "expectedInputDocuments");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedInputDocuments must not be empty");
        }
        requireDocumentOrder(copy);
        return Collections.unmodifiableList(copy);
    }

    private static List<DocumentDelta> canonicalDocumentDeltas(
            List<DocumentDelta> values) {
        ArrayList<DocumentDelta> copy = copy(values, "resultingDocuments");
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).documentId().compareTo(
                    copy.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "resultingDocuments are not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<InputComponent> distinctInputComponents(
            List<InputComponent> values) {
        ArrayList<InputComponent> copy = copy(
                values, "expectedInputComponents");
        requireDistinctComponents(copy, "expectedInputComponents");
        return Collections.unmodifiableList(copy);
    }

    private static List<ResultComponent> distinctResultComponents(
            List<ResultComponent> values) {
        ArrayList<ResultComponent> copy = copy(
                values, "resultingComponents");
        requireDistinctComponents(copy, "resultingComponents");
        return Collections.unmodifiableList(copy);
    }

    private static <T> ArrayList<T> copy(List<T> values, String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T item : copy) {
            Objects.requireNonNull(item, field + " item");
        }
        return copy;
    }

    private static void requireDocumentOrder(List<InputDocument> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).documentId().compareTo(
                    values.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "expectedInputDocuments are not in canonical order");
            }
        }
    }

    private static void requireDistinctComponents(
            List<? extends ComponentIdentity> values,
            String field) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        Set<String> identities = new HashSet<String>();
        for (ComponentIdentity value : values) {
            if (!identities.add(value.componentIdentity())) {
                throw new IllegalArgumentException(
                        field + " contains a duplicate componentIdentity");
            }
        }
    }

    private static List<Object> inputDocumentValues(List<InputDocument> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (InputDocument item : values) {
            result.add(item.identityValue());
        }
        return result;
    }

    private static List<Object> inputComponentValues(
            List<InputComponent> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (InputComponent item : values) {
            result.add(item.identityValue());
        }
        return result;
    }

    private static List<Object> documentDeltaValues(
            List<DocumentDelta> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (DocumentDelta item : values) {
            result.add(item.identityValue());
        }
        return result;
    }

    private static List<Object> resultComponentValues(
            List<ResultComponent> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (ResultComponent item : values) {
            result.add(item.identityValue());
        }
        return result;
    }

    private interface ComponentIdentity {
        String componentIdentity();
    }

    /** Exact input document compare-and-swap expectation. */
    public static final class InputDocument {
        private final DocumentId documentId;
        private final String blueId;

        /**
         * Creates one exact input document expectation.
         *
         * @param documentId stable input lineage
         * @param blueId expected exact input identity
         */
        public InputDocument(DocumentId documentId, String blueId) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.blueId = ClosureValueSupport.requireBlueId(blueId, "blueId");
        }

        /**
         * Returns stable input lineage.
         *
         * @return stable input lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns expected exact input identity.
         *
         * @return expected exact input identity
         */
        public String blueId() {
            return blueId;
        }

        private Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("documentId", documentId.value());
            value.put("blueId", blueId);
            return value;
        }
    }

    /** Exact input component compare-and-swap expectation. */
    public static final class InputComponent implements ComponentIdentity {
        private final String componentIdentity;
        private final String componentStateIdentity;
        private final long componentGeneration;
        private final String masterBlueId;

        /**
         * Creates one exact input component expectation.
         *
         * @param componentIdentity stable component lineage identity
         * @param componentStateIdentity expected exact state identity
         * @param componentGeneration expected generation
         * @param masterBlueId expected cyclic MASTER, or null for acyclic
         */
        public InputComponent(
                String componentIdentity,
                String componentStateIdentity,
                long componentGeneration,
                String masterBlueId) {
            this.componentIdentity = identity(
                    componentIdentity, "componentIdentity");
            this.componentStateIdentity = identity(
                    componentStateIdentity, "componentStateIdentity");
            this.componentGeneration =
                    ClosureValueSupport.requireSafeInteger(
                            componentGeneration, "componentGeneration");
            this.masterBlueId = masterBlueId == null ? null
                    : ClosureValueSupport.requireBlueId(
                            masterBlueId, "masterBlueId");
        }

        /**
         * Returns stable component lineage identity.
         *
         * @return stable component lineage identity
         */
        @Override
        public String componentIdentity() {
            return componentIdentity;
        }

        /**
         * Returns expected exact component-state identity.
         *
         * @return expected exact component-state identity
         */
        public String componentStateIdentity() {
            return componentStateIdentity;
        }

        /**
         * Returns expected component generation.
         *
         * @return expected component generation
         */
        public long componentGeneration() {
            return componentGeneration;
        }

        /**
         * Returns expected cyclic MASTER, or null for acyclic.
         *
         * @return expected cyclic MASTER, or null for acyclic
         */
        public String masterBlueId() {
            return masterBlueId;
        }

        private Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("componentIdentity", componentIdentity);
            value.put("componentStateIdentity", componentStateIdentity);
            value.put("componentGeneration",
                    Long.valueOf(componentGeneration));
            value.put("masterBlueId", masterBlueId);
            return value;
        }
    }

    /** Exact changed document identity triple. */
    public static final class DocumentDelta {
        private final DocumentId documentId;
        private final String beforeBlueId;
        private final String afterBlueId;

        /**
         * Creates one changed document identity triple.
         *
         * @param documentId stable managed lineage
         * @param beforeBlueId exact predecessor identity
         * @param afterBlueId exact successor identity
         */
        public DocumentDelta(
                DocumentId documentId,
                String beforeBlueId,
                String afterBlueId) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.beforeBlueId = ClosureValueSupport.requireBlueId(
                    beforeBlueId, "beforeBlueId");
            this.afterBlueId = ClosureValueSupport.requireBlueId(
                    afterBlueId, "afterBlueId");
            if (this.beforeBlueId.equals(this.afterBlueId)) {
                throw new IllegalArgumentException(
                        "A commit document delta cannot be a no-op");
            }
        }

        /**
         * Returns stable managed lineage.
         *
         * @return stable managed lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns exact predecessor identity.
         *
         * @return exact predecessor identity
         */
        public String beforeBlueId() {
            return beforeBlueId;
        }

        /**
         * Returns exact successor identity.
         *
         * @return exact successor identity
         */
        public String afterBlueId() {
            return afterBlueId;
        }

        private Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("documentId", documentId.value());
            value.put("beforeBlueId", beforeBlueId);
            value.put("afterBlueId", afterBlueId);
            return value;
        }
    }

    /** Exact staged component identity triple. */
    public static final class ResultComponent implements ComponentIdentity {
        private final String componentIdentity;
        private final String componentStateIdentity;
        private final String cyclicProofIdentity;

        /**
         * Creates one exact staged component identity triple.
         *
         * @param componentIdentity stable component lineage identity
         * @param componentStateIdentity exact staged state identity
         * @param cyclicProofIdentity exact cyclic proof identity, or null
         */
        public ResultComponent(
                String componentIdentity,
                String componentStateIdentity,
                String cyclicProofIdentity) {
            this.componentIdentity = identity(
                    componentIdentity, "componentIdentity");
            this.componentStateIdentity = identity(
                    componentStateIdentity, "componentStateIdentity");
            this.cyclicProofIdentity = cyclicProofIdentity == null ? null
                    : identity(cyclicProofIdentity, "cyclicProofIdentity");
        }

        /**
         * Returns stable component lineage identity.
         *
         * @return stable component lineage identity
         */
        @Override
        public String componentIdentity() {
            return componentIdentity;
        }

        /**
         * Returns exact staged component-state identity.
         *
         * @return exact staged component-state identity
         */
        public String componentStateIdentity() {
            return componentStateIdentity;
        }

        /**
         * Returns exact cyclic proof identity, or null for acyclic.
         *
         * @return exact cyclic proof identity, or null for acyclic
         */
        public String cyclicProofIdentity() {
            return cyclicProofIdentity;
        }

        private Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("componentIdentity", componentIdentity);
            value.put("componentStateIdentity", componentStateIdentity);
            value.put("cyclicProofIdentity", cyclicProofIdentity);
            return value;
        }
    }
}
