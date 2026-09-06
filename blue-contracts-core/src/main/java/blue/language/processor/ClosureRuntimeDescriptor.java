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
            "sha256:0b4bd3bbe4380faa52d14bc6baf8bb0a6dbc01acc576985676155ea0115969b4";

    /** Released Language 1.0 cyclic-proof verifier semantic identity. */
    public static final String CYCLIC_PROOF_VERIFIER_IDENTITY =
            "sha256:eb0501a25ec5ac6a18fc86584c0afb6ecc2e6c1201c723f28ec56c80a2ae3bc5";

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
                registryEvidenceIdentity(selected.runtimeRegistryIdentity()),
                "sha256:" + GasSchedule.CONTRACTS_1_0_RESOURCE_SHA256);
    }

    /** Canonical owning bridge from the generated registry's content BlueId. */
    static String registryEvidenceIdentity(String identity) {
        Objects.requireNonNull(identity, "runtimeRegistryIdentity");
        if (identity.matches("sha256:[0-9a-f]{64}")) return identity;
        blue.language.identity.BlueIds.requirePlainBlueId(identity, "runtimeRegistryIdentity");
        // A validated Base58 value needs no JSON escaping. This fixed field
        // order is the RFC 8785 representation of the closed constructor.
        String canonical = "{\"domain\":\"blue-contracts-runtime-registry-blueid/1\",\"value\":{\"blueId\":\""
                + identity + "\"}}";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
