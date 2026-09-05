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
            "sha256:5d62d029bc2e9072ec8e65b0a4ea2db04a42facc6a6bcd069d694bf69d08922d";

    /** Released Language 1.0 cyclic-proof verifier semantic identity. */
    public static final String CYCLIC_PROOF_VERIFIER_IDENTITY =
            "sha256:30785ca04af90a4c2d303a658ed7354ee82d718d05d394623889943f285e429d";

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
