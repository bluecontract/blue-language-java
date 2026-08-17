package blue.language.processor.closure;

/** Immutable identity snapshot of every runtime and policy dependency. */
public final class ClosureEnvironment {

    private final String blueLanguageSpecificationIdentity;
    private final String contractsSpecificationIdentity;
    private final String runtimeRegistryIdentity;
    private final String gasManifestIdentity;
    private final String managedDocumentIdentityPolicyIdentity;
    private final String managedBindingPolicyIdentity;
    private final String exactNodeProviderDomainIdentity;
    private final String externalOrderPolicyIdentity;
    private final String portableLimitPolicyIdentity;
    private final String cyclicFinalizerIdentity;
    private final String cyclicProofVerifierIdentity;

    /**
     * Creates the complete identity-bound closure execution environment.
     *
     * @param blueLanguageSpecificationIdentity selected Language specification
     * @param contractsSpecificationIdentity selected Contracts specification
     * @param runtimeRegistryIdentity frozen runtime registry generation
     * @param gasManifestIdentity selected gas manifest
     * @param managedDocumentIdentityPolicyIdentity document-lineage policy
     * @param managedBindingPolicyIdentity occurrence-binding policy
     * @param exactNodeProviderDomainIdentity exact-node provider domain
     * @param externalOrderPolicyIdentity external total-order policy
     * @param portableLimitPolicyIdentity selected portable-limit policy
     * @param cyclicFinalizerIdentity selected Language cyclic finalizer
     * @param cyclicProofVerifierIdentity selected cyclic proof verifier
     */
    public ClosureEnvironment(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            String runtimeRegistryIdentity,
            String gasManifestIdentity,
            String managedDocumentIdentityPolicyIdentity,
            String managedBindingPolicyIdentity,
            String exactNodeProviderDomainIdentity,
            String externalOrderPolicyIdentity,
            String portableLimitPolicyIdentity,
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
        this.managedDocumentIdentityPolicyIdentity = identity(
                managedDocumentIdentityPolicyIdentity,
                "managedDocumentIdentityPolicyIdentity");
        this.managedBindingPolicyIdentity = identity(
                managedBindingPolicyIdentity,
                "managedBindingPolicyIdentity");
        this.exactNodeProviderDomainIdentity = identity(
                exactNodeProviderDomainIdentity,
                "exactNodeProviderDomainIdentity");
        this.externalOrderPolicyIdentity = identity(
                externalOrderPolicyIdentity,
                "externalOrderPolicyIdentity");
        this.portableLimitPolicyIdentity = identity(
                portableLimitPolicyIdentity,
                "portableLimitPolicyIdentity");
        this.cyclicFinalizerIdentity = identity(
                cyclicFinalizerIdentity, "cyclicFinalizerIdentity");
        this.cyclicProofVerifierIdentity = identity(
                cyclicProofVerifierIdentity,
                "cyclicProofVerifierIdentity");
    }

    /**
     * Returns the documented value.
     *
     * @return selected Language specification identity
     */
    public String blueLanguageSpecificationIdentity() {
        return blueLanguageSpecificationIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected Contracts specification identity
     */
    public String contractsSpecificationIdentity() {
        return contractsSpecificationIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return frozen runtime registry generation identity
     */
    public String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected gas manifest identity
     */
    public String gasManifestIdentity() {
        return gasManifestIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return managed-document identity-policy identity
     */
    public String managedDocumentIdentityPolicyIdentity() {
        return managedDocumentIdentityPolicyIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return managed occurrence-binding-policy identity
     */
    public String managedBindingPolicyIdentity() {
        return managedBindingPolicyIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return exact-node provider-domain identity
     */
    public String exactNodeProviderDomainIdentity() {
        return exactNodeProviderDomainIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return external total-order-policy identity
     */
    public String externalOrderPolicyIdentity() {
        return externalOrderPolicyIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected portable-limit-policy identity
     */
    public String portableLimitPolicyIdentity() {
        return portableLimitPolicyIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected Language cyclic-finalizer identity
     */
    public String cyclicFinalizerIdentity() {
        return cyclicFinalizerIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected cyclic-proof-verifier identity
     */
    public String cyclicProofVerifierIdentity() {
        return cyclicProofVerifierIdentity;
    }

    private static String identity(String value, String field) {
        return ClosureValueSupport.requireSha256Identity(value, field);
    }
}
