package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Complete immutable authoritative closure state. Invocation-only cause,
 * delivery, historical-resource, candidate, gas-policy and environment
 * evidence lives in {@link ClosureInvocationInput}.
 */
public final class AffectedClosureSnapshot {
    /** Recomputes the identity of authoritative closure state only. */
    public interface ClosureIdentityFactory {
        String identity(
                long graphGeneration,
                Map<DocumentId, ManagedDocument> documents,
                String occurrenceBindingSetIdentity,
                List<ComponentSnapshot> components,
                List<DocumentId> publicRootDocumentIds);
    }

    private final String closureIdentity;
    private final long graphGeneration;
    private final Map<DocumentId, ManagedDocument> documents;
    private final List<ManagedOccurrenceBinding> occurrences;
    private final List<ComponentSnapshot> components;
    private final String occurrenceBindingSetIdentity;
    private final List<DocumentId> publicRootDocumentIds;

    public AffectedClosureSnapshot(
            String closureIdentity,
            long graphGeneration,
            Map<DocumentId, ManagedDocument> documents,
            List<ManagedOccurrenceBinding> occurrences,
            List<ComponentSnapshot> components,
            String occurrenceBindingSetIdentity,
            ManagedOccurrenceBinding.BindingSetIdentityFactory bindingSetIdentityFactory,
            List<DocumentId> publicRootDocumentIds,
            ClosureIdentityFactory closureIdentityFactory) {
        this.graphGeneration = CanonicalOrders.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.documents = immutableDocuments(documents);
        this.occurrences = immutableCanonicalOccurrences(occurrences);
        this.components = immutableList(components, "components");
        String recomputedBindingSetIdentity = Objects.requireNonNull(
                Objects.requireNonNull(
                        bindingSetIdentityFactory, "bindingSetIdentityFactory")
                        .identity(this.occurrences),
                "recomputed occurrenceBindingSetIdentity");
        if (!recomputedBindingSetIdentity.equals(Objects.requireNonNull(
                occurrenceBindingSetIdentity, "occurrenceBindingSetIdentity"))) {
            throw new IllegalArgumentException("occurrenceBindingSetIdentity mismatch");
        }
        this.occurrenceBindingSetIdentity = recomputedBindingSetIdentity;
        this.publicRootDocumentIds = immutableCanonicalDocumentIds(
                publicRootDocumentIds, "publicRootDocumentIds");
        String recomputedClosureIdentity = Objects.requireNonNull(
                Objects.requireNonNull(
                        closureIdentityFactory, "closureIdentityFactory")
                        .identity(
                                this.graphGeneration,
                                this.documents,
                                this.occurrenceBindingSetIdentity,
                                this.components,
                                this.publicRootDocumentIds),
                "recomputed closureIdentity");
        if (!recomputedClosureIdentity.equals(Objects.requireNonNull(
                closureIdentity, "closureIdentity"))) {
            throw new IllegalArgumentException("closureIdentity mismatch");
        }
        this.closureIdentity = recomputedClosureIdentity;
    }

    private static Map<DocumentId, ManagedDocument> immutableDocuments(
            Map<DocumentId, ManagedDocument> values) {
        TreeMap<DocumentId, ManagedDocument> sorted =
                new TreeMap<DocumentId, ManagedDocument>(CanonicalOrders.DOCUMENT_ID);
        for (Map.Entry<DocumentId, ManagedDocument> entry
                : Objects.requireNonNull(values, "documents").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "document key");
            ManagedDocument document = Objects.requireNonNull(
                    entry.getValue(), "document value");
            if (!documentId.equals(document.documentId())) {
                throw new IllegalArgumentException("document key mismatch");
            }
            sorted.put(documentId, document);
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("documents");
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<DocumentId, ManagedDocument>(sorted));
    }

    private static <T> List<T> immutableList(List<T> values, String field) {
        ArrayList<T> copy = new ArrayList<T>(Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ManagedOccurrenceBinding> immutableCanonicalOccurrences(
            List<ManagedOccurrenceBinding> values) {
        ArrayList<ManagedOccurrenceBinding> copy = new ArrayList<ManagedOccurrenceBinding>(
                Objects.requireNonNull(values, "occurrences"));
        ManagedOccurrenceBinding previous = null;
        for (ManagedOccurrenceBinding value : copy) {
            ManagedOccurrenceBinding current = Objects.requireNonNull(
                    value, "occurrences item");
            if (previous != null) {
                int occurrenceOrder = previous.occurrenceIdentity().compareTo(
                        current.occurrenceIdentity());
                if (occurrenceOrder > 0
                        || (occurrenceOrder == 0
                        && previous.bindingIdentity().compareTo(
                                current.bindingIdentity()) >= 0)) {
                    throw new IllegalArgumentException(
                            "occurrences not in canonical order");
                }
            }
            previous = current;
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<DocumentId> immutableCanonicalDocumentIds(
            List<DocumentId> values, String field) {
        ArrayList<DocumentId> copy = new ArrayList<DocumentId>(
                Objects.requireNonNull(values, field));
        for (DocumentId value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(field + " not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    public String closureIdentity() { return closureIdentity; }
    public long graphGeneration() { return graphGeneration; }
    public Map<DocumentId, ManagedDocument> documents() { return documents; }
    public List<ManagedOccurrenceBinding> occurrences() { return occurrences; }
    public List<ComponentSnapshot> components() { return components; }
    public String occurrenceBindingSetIdentity() { return occurrenceBindingSetIdentity; }
    public List<DocumentId> publicRootDocumentIds() { return publicRootDocumentIds; }

    /** One authoritative managed-document record in the frozen closure. */
    public static final class ManagedDocument {
        private final DocumentId documentId;
        private final String blueId;
        private final Object document;
        private final boolean initialized;
        private final boolean terminated;
        private final boolean publicRoot;
        private final long epoch;
        private final long componentGeneration;

        public ManagedDocument(
                DocumentId documentId,
                String blueId,
                Object document,
                boolean initialized,
                boolean terminated,
                boolean publicRoot,
                long epoch,
                long componentGeneration) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.blueId = Objects.requireNonNull(blueId, "blueId");
            this.document = Objects.requireNonNull(document, "document");
            this.initialized = initialized;
            this.terminated = terminated;
            this.publicRoot = publicRoot;
            this.epoch = CanonicalOrders.requireSafeInteger(epoch, "epoch");
            this.componentGeneration = CanonicalOrders.requireSafeInteger(
                    componentGeneration, "componentGeneration");
        }

        public DocumentId documentId() { return documentId; }
        public String blueId() { return blueId; }
        public Object document() { return document; }
        public boolean initialized() { return initialized; }
        public boolean terminated() { return terminated; }
        public boolean publicRoot() { return publicRoot; }
        public long epoch() { return epoch; }
        public long componentGeneration() { return componentGeneration; }
    }

    /** Constructible label-based policy evidence and its exact identity. */
    public static final class IdentityPolicyEvidence {
        private final String identity;
        private final String label;

        public IdentityPolicyEvidence(String identity, String label) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.label = Objects.requireNonNull(label, "label");
        }

        public String identity() { return identity; }
        public String label() { return label; }
    }

    /** One exact portable named limit. */
    public static final class PortableLimit {
        private final String name;
        private final long value;

        public PortableLimit(String name, long value) {
            this.name = Objects.requireNonNull(name, "name");
            this.value = CanonicalOrders.requireSafeInteger(value, "value");
        }

        public String name() { return name; }
        public long value() { return value; }
    }

    /** Exact portable-limit policy evidence. */
    public static final class PortableLimitPolicyEvidence {
        private final String identity;
        private final String label;
        private final List<PortableLimit> limits;

        public PortableLimitPolicyEvidence(
                String identity, String label, List<PortableLimit> limits) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.label = Objects.requireNonNull(label, "label");
            ArrayList<PortableLimit> copy = new ArrayList<PortableLimit>(
                    Objects.requireNonNull(limits, "limits"));
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("limits");
            }
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).name().compareTo(copy.get(index).name()) >= 0) {
                    throw new IllegalArgumentException("limits not in canonical order");
                }
            }
            this.limits = Collections.unmodifiableList(copy);
        }

        public String identity() { return identity; }
        public String label() { return label; }
        public List<PortableLimit> limits() { return limits; }
    }

    /** Fixed release, registry, provider and policy identities for one attempt. */
    public static final class EnvironmentEvidence {
        private final String blueLanguageSpecificationIdentity;
        private final String contractsSpecificationIdentity;
        private final String runtimeRegistryIdentity;
        private final String gasManifestIdentity;
        private final IdentityPolicyEvidence managedDocumentIdentityPolicy;
        private final IdentityPolicyEvidence managedBindingPolicy;
        private final IdentityPolicyEvidence exactNodeProviderDomain;
        private final IdentityPolicyEvidence externalOrderPolicy;
        private final PortableLimitPolicyEvidence portableLimitPolicy;
        private final String cyclicFinalizerIdentity;
        private final String cyclicProofVerifierIdentity;

        public EnvironmentEvidence(
                String blueLanguageSpecificationIdentity,
                String contractsSpecificationIdentity,
                String runtimeRegistryIdentity,
                String gasManifestIdentity,
                IdentityPolicyEvidence managedDocumentIdentityPolicy,
                IdentityPolicyEvidence managedBindingPolicy,
                IdentityPolicyEvidence exactNodeProviderDomain,
                IdentityPolicyEvidence externalOrderPolicy,
                PortableLimitPolicyEvidence portableLimitPolicy,
                String cyclicFinalizerIdentity,
                String cyclicProofVerifierIdentity) {
            this.blueLanguageSpecificationIdentity = Objects.requireNonNull(
                    blueLanguageSpecificationIdentity, "blueLanguageSpecificationIdentity");
            this.contractsSpecificationIdentity = Objects.requireNonNull(
                    contractsSpecificationIdentity, "contractsSpecificationIdentity");
            this.runtimeRegistryIdentity = Objects.requireNonNull(
                    runtimeRegistryIdentity, "runtimeRegistryIdentity");
            this.gasManifestIdentity = Objects.requireNonNull(
                    gasManifestIdentity, "gasManifestIdentity");
            this.managedDocumentIdentityPolicy = Objects.requireNonNull(
                    managedDocumentIdentityPolicy, "managedDocumentIdentityPolicy");
            this.managedBindingPolicy = Objects.requireNonNull(
                    managedBindingPolicy, "managedBindingPolicy");
            this.exactNodeProviderDomain = Objects.requireNonNull(
                    exactNodeProviderDomain, "exactNodeProviderDomain");
            this.externalOrderPolicy = Objects.requireNonNull(
                    externalOrderPolicy, "externalOrderPolicy");
            this.portableLimitPolicy = Objects.requireNonNull(
                    portableLimitPolicy, "portableLimitPolicy");
            this.cyclicFinalizerIdentity = Objects.requireNonNull(
                    cyclicFinalizerIdentity, "cyclicFinalizerIdentity");
            this.cyclicProofVerifierIdentity = Objects.requireNonNull(
                    cyclicProofVerifierIdentity, "cyclicProofVerifierIdentity");
        }

        public String blueLanguageSpecificationIdentity() { return blueLanguageSpecificationIdentity; }
        public String contractsSpecificationIdentity() { return contractsSpecificationIdentity; }
        public String runtimeRegistryIdentity() { return runtimeRegistryIdentity; }
        public String gasManifestIdentity() { return gasManifestIdentity; }
        public IdentityPolicyEvidence managedDocumentIdentityPolicy() { return managedDocumentIdentityPolicy; }
        public IdentityPolicyEvidence managedBindingPolicy() { return managedBindingPolicy; }
        public IdentityPolicyEvidence exactNodeProviderDomain() { return exactNodeProviderDomain; }
        public IdentityPolicyEvidence externalOrderPolicy() { return externalOrderPolicy; }
        public PortableLimitPolicyEvidence portableLimitPolicy() { return portableLimitPolicy; }
        public String cyclicFinalizerIdentity() { return cyclicFinalizerIdentity; }
        public String cyclicProofVerifierIdentity() { return cyclicProofVerifierIdentity; }
    }
}
