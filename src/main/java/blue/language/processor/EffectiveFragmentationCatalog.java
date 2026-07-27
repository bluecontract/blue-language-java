package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable effective fragmentation boundaries for one exact Processing Root.
 *
 * <p>The catalog is an out-of-band inspection value. It executes no contract,
 * consumes no Contracts gas, creates no checkpoint, and never manufactures a
 * BlueId for an effective merged contract.</p>
 */
public final class EffectiveFragmentationCatalog {

    private final String rootBlueId;
    private final Map<String, List<String>>
            effectiveProcessEmbeddedPathsByScope;
    private final Map<String, List<EffectiveContractSnapshot>>
            effectiveContractsByScope;

    EffectiveFragmentationCatalog(
            String rootBlueId,
            Map<String, List<String>>
                    effectiveProcessEmbeddedPathsByScope,
            Map<String, List<EffectiveContractSnapshot>>
                    effectiveContractsByScope) {
        this.rootBlueId =
                Objects.requireNonNull(rootBlueId, "rootBlueId");
        this.effectiveProcessEmbeddedPathsByScope =
                immutableLists(
                        effectiveProcessEmbeddedPathsByScope,
                        "effectiveProcessEmbeddedPathsByScope");
        this.effectiveContractsByScope =
                immutableLists(
                        effectiveContractsByScope,
                        "effectiveContractsByScope");
        if (!this.effectiveProcessEmbeddedPathsByScope.keySet()
                .equals(this.effectiveContractsByScope.keySet())) {
            throw new IllegalArgumentException(
                    "Catalog scope surfaces must have identical keys");
        }
    }

    /**
     * Exact identity of the inspected canonical Root.
     */
    public String rootBlueId() {
        return rootBlueId;
    }

    /**
     * Effective normalized Process Embedded paths by active scope.
     *
     * <p>Scope keys are root-first and deterministic. Path list order remains
     * the effective Process Embedded list order because list order is semantic
     * Blue content.</p>
     */
    public Map<String, List<String>>
    effectiveProcessEmbeddedPathsByScope() {
        return effectiveProcessEmbeddedPathsByScope;
    }

    /**
     * Effective contracts by active scope.
     *
     * <p>Entries are ordered by raw contract-key Unicode code points. Each
     * snapshot retains its exact ancestor-to-descendant contribution
     * identities and exact registered executable-body boundaries.</p>
     */
    public Map<String, List<EffectiveContractSnapshot>>
    effectiveContractsByScope() {
        return effectiveContractsByScope;
    }

    private static <T> Map<String, List<T>> immutableLists(
            Map<String, List<T>> source,
            String label) {
        Objects.requireNonNull(source, label);
        Map<String, List<T>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(
                            entry.getKey(), label + " scope"),
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    Objects.requireNonNull(
                                            entry.getValue(),
                                            label + " value"))));
        }
        return Collections.unmodifiableMap(copy);
    }
}
