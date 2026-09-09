package blue.language.processor;

import java.util.Objects;

/**
 * Immutable identity binding for the production affected-closure runtime.
 *
 * <p>This type is the narrow public bridge by which the closure package can
 * prove that invocation evidence selected the configured processor registry
 * and the exact shipped gas manifest.  It exposes no mutable processor
 * service.</p>
 */
public final class ClosureRuntimeDescriptor {

    /** Released Language 1.0 cyclic-set finalizer semantic identity. */
    public static final String CYCLIC_FINALIZER_IDENTITY =
            "sha256:f8e41baf14343d05b065745c3d1c569dd15e3fbae09331757efd2f7ffbf6f52b";

    /** Released Language 1.0 cyclic-proof verifier semantic identity. */
    public static final String CYCLIC_PROOF_VERIFIER_IDENTITY =
            "sha256:0d1a9ab0ee17712521cccb8d988d838c5b7de949b1d168a1c204ac2538e71834";

    private final String runtimeRegistryIdentity;
    private final String gasManifestIdentity;

    private ClosureRuntimeDescriptor(
            String runtimeRegistryIdentity,
            String gasManifestIdentity) {
        this.runtimeRegistryIdentity = Objects.requireNonNull(
                runtimeRegistryIdentity, "runtimeRegistryIdentity");
        this.gasManifestIdentity = Objects.requireNonNull(
                gasManifestIdentity, "gasManifestIdentity");
    }

    /**
     * Captures immutable configuration identities from one processor.
     *
     * <p>Only the released manifest currently has an exact raw-resource
     * digest binding.  A custom schedule fails closed until its builder also
     * carries an exact manifest identity.</p>
     *
     * @param processor configured processor generation
     * @return exact immutable runtime descriptor
     */
    public static ClosureRuntimeDescriptor capture(
            DocumentProcessor processor) {
        DocumentProcessor selected = Objects.requireNonNull(
                processor, "processor");
        GasSchedule gas = selected.gasSchedule();
        if (!GasSchedule.CONTRACTS_1_0_SCHEDULE.equals(gas.schedule())
                || !GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY.equals(
                        gas.packageIdentity())) {
            throw new IllegalStateException(
                    "Custom gas schedules require an exact manifest identity binding");
        }
        return new ClosureRuntimeDescriptor(
                selected.runtimeRegistryIdentity(),
                "sha256:" + GasSchedule.CONTRACTS_1_0_RESOURCE_SHA256);
    }

    /**
     * Returns the captured configured runtime-registry identity.
     *
     * @return SHA-256 registry identity
     */
    public String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    /**
     * Returns the exact raw gas-manifest identity.
     *
     * @return SHA-256 manifest identity
     */
    public String gasManifestIdentity() {
        return gasManifestIdentity;
    }
}
