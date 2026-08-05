package blue.language.processor;

import blue.language.processor.util.PointerUtils;

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
    private final Map<String, EmbeddedScopePlanView> scopePlansByScope;
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
        this(
                rootBlueId,
                legacyPlans(effectiveProcessEmbeddedPathsByScope),
                effectiveProcessEmbeddedPathsByScope,
                effectiveContractsByScope);
    }

    EffectiveFragmentationCatalog(
            String rootBlueId,
            Map<String, EmbeddedScopePlanView> scopePlansByScope,
            Map<String, List<String>>
                    effectiveProcessEmbeddedPathsByScope,
            Map<String, List<EffectiveContractSnapshot>>
                    effectiveContractsByScope) {
        this.rootBlueId =
                Objects.requireNonNull(rootBlueId, "rootBlueId");
        this.scopePlansByScope = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        scopePlansByScope, "scopePlansByScope")));
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
        if (!this.scopePlansByScope.keySet().equals(
                this.effectiveContractsByScope.keySet())) {
            throw new IllegalArgumentException(
                    "Catalog scope plans and contracts must have identical keys");
        }
    }

    /**
     * Exact identity of the inspected canonical Root.
     *
     * @return canonical Root BlueId
     */
    public String rootBlueId() {
        return rootBlueId;
    }

    /**
     * Structured effective Process Embedded plans by active scope.
     *
     * <p>Every active scope has one view. A scope without an effective Process
     * Embedded contract has an empty view, preserving root-first catalog key
     * order without conflating absence with another scope's declaration.</p>
     *
     * @return immutable scope-to-plan mapping
     */
    public Map<String, EmbeddedScopePlanView> scopePlansByScope() {
        return scopePlansByScope;
    }

    /**
     * Effective concrete Process Embedded child paths by active scope.
     *
     * <p>Scope keys are root-first and deterministic. Each value combines
     * present exact children with generated stable-key collection members in
     * canonical Runtime Pointer order. Declaration order remains separately
     * available from {@link #scopePlansByScope()}.</p>
     *
     * @return deeply unmodifiable scope-to-path mapping
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
     *
     * @return deeply unmodifiable scope-to-snapshot mapping
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

    private static Map<String, EmbeddedScopePlanView> legacyPlans(
            Map<String, List<String>> pathsByScope) {
        Objects.requireNonNull(pathsByScope,
                "effectiveProcessEmbeddedPathsByScope");
        Map<String, EmbeddedScopePlanView> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry
                : pathsByScope.entrySet()) {
            List<String> concrete = new ArrayList<>();
            Map<String, EmbeddedScopePlanView.Origin> origins =
                    new LinkedHashMap<>();
            for (String path : entry.getValue()) {
                String absolute = PointerUtils.resolvePointer(
                        entry.getKey(), path);
                concrete.add(absolute);
                origins.put(
                        absolute,
                        EmbeddedScopePlanView.Origin.EXPLICIT);
            }
            result.put(
                    entry.getKey(),
                    new EmbeddedScopePlanView(
                            entry.getKey(),
                            entry.getValue(),
                            Collections.<String>emptyList(),
                            Collections.<String, List<String>>emptyMap(),
                            concrete,
                            origins));
        }
        return result;
    }
}
