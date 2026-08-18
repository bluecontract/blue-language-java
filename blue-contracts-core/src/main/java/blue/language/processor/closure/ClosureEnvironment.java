package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable identity snapshot of every runtime and policy dependency.
 *
 * <p>The five portable policy/domain identities retain their complete local
 * constructor evidence.  Specification, runtime-registry, gas-manifest,
 * cyclic-finalizer, and proof-verifier identities are external artifact
 * digests: this value binds and validates their digest syntax but does not
 * contain the external artifact bytes needed to recreate them.</p>
 */
public final class ClosureEnvironment {

    private final String blueLanguageSpecificationIdentity;
    private final String contractsSpecificationIdentity;
    private final String runtimeRegistryIdentity;
    private final String gasManifestIdentity;
    private final LabeledIdentityEvidence managedDocumentIdentityPolicy;
    private final LabeledIdentityEvidence managedBindingPolicy;
    private final LabeledIdentityEvidence exactNodeProviderDomain;
    private final LabeledIdentityEvidence externalOrderPolicy;
    private final PortableLimitPolicyEvidence portableLimitPolicy;
    private final String cyclicFinalizerIdentity;
    private final String cyclicProofVerifierIdentity;

    /**
     * Creates the complete identity-bound closure execution environment.
     *
     * @param blueLanguageSpecificationIdentity selected Language artifact digest
     * @param contractsSpecificationIdentity selected Contracts artifact digest
     * @param runtimeRegistryIdentity frozen runtime-registry artifact digest
     * @param gasManifestIdentity selected gas-manifest artifact digest
     * @param managedDocumentIdentityPolicy complete document-policy evidence
     * @param managedBindingPolicy complete occurrence-policy evidence
     * @param exactNodeProviderDomain complete exact-provider-domain evidence
     * @param externalOrderPolicy complete external-order-policy evidence
     * @param portableLimitPolicy complete portable-limit-policy evidence
     * @param cyclicFinalizerIdentity selected Language finalizer artifact digest
     * @param cyclicProofVerifierIdentity selected proof-verifier artifact digest
     */
    public ClosureEnvironment(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            String runtimeRegistryIdentity,
            String gasManifestIdentity,
            LabeledIdentityEvidence managedDocumentIdentityPolicy,
            LabeledIdentityEvidence managedBindingPolicy,
            LabeledIdentityEvidence exactNodeProviderDomain,
            LabeledIdentityEvidence externalOrderPolicy,
            PortableLimitPolicyEvidence portableLimitPolicy,
            String cyclicFinalizerIdentity,
            String cyclicProofVerifierIdentity) {
        this.blueLanguageSpecificationIdentity = identity(
                blueLanguageSpecificationIdentity,
                "blueLanguageSpecificationIdentity");
        this.contractsSpecificationIdentity = identity(
                contractsSpecificationIdentity,
                "contractsSpecificationIdentity");
        this.runtimeRegistryIdentity = identity(
                runtimeRegistryIdentity, "runtimeRegistryIdentity");
        this.gasManifestIdentity = identity(
                gasManifestIdentity, "gasManifestIdentity");
        this.managedDocumentIdentityPolicy = Objects.requireNonNull(
                managedDocumentIdentityPolicy,
                "managedDocumentIdentityPolicy");
        this.managedBindingPolicy = Objects.requireNonNull(
                managedBindingPolicy, "managedBindingPolicy");
        this.exactNodeProviderDomain = Objects.requireNonNull(
                exactNodeProviderDomain, "exactNodeProviderDomain");
        this.externalOrderPolicy = Objects.requireNonNull(
                externalOrderPolicy, "externalOrderPolicy");
        this.portableLimitPolicy = Objects.requireNonNull(
                portableLimitPolicy, "portableLimitPolicy");
        this.cyclicFinalizerIdentity = identity(
                cyclicFinalizerIdentity, "cyclicFinalizerIdentity");
        this.cyclicProofVerifierIdentity = identity(
                cyclicProofVerifierIdentity,
                "cyclicProofVerifierIdentity");
    }

    /**
     * Returns the selected Language specification artifact digest.
     *
     * @return selected Language specification identity
     */
    public String blueLanguageSpecificationIdentity() {
        return blueLanguageSpecificationIdentity;
    }

    /**
     * Returns the selected Contracts specification artifact digest.
     *
     * @return selected Contracts specification identity
     */
    public String contractsSpecificationIdentity() {
        return contractsSpecificationIdentity;
    }

    /**
     * Returns the frozen runtime-registry artifact digest.
     *
     * @return frozen runtime registry identity
     */
    public String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    /**
     * Returns the selected gas-manifest artifact digest.
     *
     * @return selected gas manifest identity
     */
    public String gasManifestIdentity() {
        return gasManifestIdentity;
    }

    /**
     * Returns complete managed-document identity-policy evidence.
     *
     * @return immutable labeled policy evidence
     */
    public LabeledIdentityEvidence managedDocumentIdentityPolicy() {
        return managedDocumentIdentityPolicy;
    }

    /**
     * Returns the managed-document identity-policy identity.
     *
     * @return document-lineage policy identity
     */
    public String managedDocumentIdentityPolicyIdentity() {
        return managedDocumentIdentityPolicy.identity();
    }

    /**
     * Returns complete managed occurrence-binding-policy evidence.
     *
     * @return immutable labeled policy evidence
     */
    public LabeledIdentityEvidence managedBindingPolicy() {
        return managedBindingPolicy;
    }

    /**
     * Returns the managed occurrence-binding-policy identity.
     *
     * @return occurrence-binding policy identity
     */
    public String managedBindingPolicyIdentity() {
        return managedBindingPolicy.identity();
    }

    /**
     * Returns complete exact-node provider-domain evidence.
     *
     * @return immutable labeled domain evidence
     */
    public LabeledIdentityEvidence exactNodeProviderDomain() {
        return exactNodeProviderDomain;
    }

    /**
     * Returns the exact-node provider-domain identity.
     *
     * @return exact-node provider domain identity
     */
    public String exactNodeProviderDomainIdentity() {
        return exactNodeProviderDomain.identity();
    }

    /**
     * Returns complete external-order-policy evidence.
     *
     * @return immutable labeled policy evidence
     */
    public LabeledIdentityEvidence externalOrderPolicy() {
        return externalOrderPolicy;
    }

    /**
     * Returns the external total-order-policy identity.
     *
     * @return external order policy identity
     */
    public String externalOrderPolicyIdentity() {
        return externalOrderPolicy.identity();
    }

    /**
     * Returns complete portable-limit-policy evidence.
     *
     * @return immutable labeled limits and claimed identity
     */
    public PortableLimitPolicyEvidence portableLimitPolicy() {
        return portableLimitPolicy;
    }

    /**
     * Returns the portable-limit-policy identity.
     *
     * @return selected portable limit policy identity
     */
    public String portableLimitPolicyIdentity() {
        return portableLimitPolicy.identity();
    }

    /**
     * Returns the selected Language cyclic-finalizer artifact digest.
     *
     * @return selected cyclic finalizer identity
     */
    public String cyclicFinalizerIdentity() {
        return cyclicFinalizerIdentity;
    }

    /**
     * Returns the selected cyclic-proof-verifier artifact digest.
     *
     * @return selected cyclic proof verifier identity
     */
    public String cyclicProofVerifierIdentity() {
        return cyclicProofVerifierIdentity;
    }

    private static String identity(String value, String field) {
        return ClosureValueSupport.requireSha256Identity(value, field);
    }

    /** Claimed identity plus the complete one-label constructor evidence. */
    public static final class LabeledIdentityEvidence {
        private final String identity;
        private final String label;

        /**
         * Creates complete evidence for a one-label constructor.
         *
         * @param identity claimed lowercase SHA-256 identity
         * @param label exact nonempty NFC constructor label
         */
        public LabeledIdentityEvidence(String identity, String label) {
            this.identity = ClosureValueSupport.requireSha256Identity(
                    identity, "identity");
            this.label = ClosureValueSupport.requireNonEmptyText(
                    label, "label");
        }

        /**
         * Returns the claimed identity.
         *
         * @return lowercase SHA-256 identity
         */
        public String identity() {
            return identity;
        }

        /**
         * Returns the exact constructor label.
         *
         * @return nonempty NFC label
         */
        public String label() {
            return label;
        }
    }

    /** Complete claimed identity, label, and named portable-limit entries. */
    public static final class PortableLimitPolicyEvidence {
        private final String identity;
        private final String label;
        private final Map<String, Long> limits;

        /**
         * Creates complete portable-limit-policy evidence.
         *
         * @param identity claimed lowercase SHA-256 identity
         * @param label exact nonempty NFC constructor label
         * @param limits complete uniquely named limit entries
         */
        public PortableLimitPolicyEvidence(
                String identity,
                String label,
                Map<String, Long> limits) {
            this.identity = ClosureValueSupport.requireSha256Identity(
                    identity, "identity");
            this.label = ClosureValueSupport.requireNonEmptyText(
                    label, "label");
            this.limits = immutableLimits(limits);
        }

        /**
         * Returns the claimed portable-limit-policy identity.
         *
         * @return lowercase SHA-256 identity
         */
        public String identity() {
            return identity;
        }

        /**
         * Returns the exact portable-limit-policy label.
         *
         * @return nonempty NFC label
         */
        public String label() {
            return label;
        }

        /**
         * Returns every limit in canonical Unicode scalar-value name order.
         *
         * @return immutable canonical named-limit map
         */
        public Map<String, Long> limits() {
            return limits;
        }

        private static Map<String, Long> immutableLimits(
                Map<String, Long> values) {
            List<Map.Entry<String, Long>> entries =
                    new ArrayList<Map.Entry<String, Long>>(
                            Objects.requireNonNull(values, "limits")
                                    .entrySet());
            Collections.sort(entries,
                    new Comparator<Map.Entry<String, Long>>() {
                        @Override
                        public int compare(
                                Map.Entry<String, Long> left,
                                Map.Entry<String, Long> right) {
                            return ClosureValueSupport.comparePortableText(
                                    left.getKey(), right.getKey());
                        }
                    });
            LinkedHashMap<String, Long> result =
                    new LinkedHashMap<String, Long>();
            for (Map.Entry<String, Long> entry : entries) {
                String name = ClosureValueSupport.requireNonEmptyText(
                        entry.getKey(), "limit name");
                long value = ClosureValueSupport.requireSafeInteger(
                        Objects.requireNonNull(
                                entry.getValue(), "limit value").longValue(),
                        "limit value");
                result.put(name, Long.valueOf(value));
            }
            return Collections.unmodifiableMap(result);
        }
    }
}
