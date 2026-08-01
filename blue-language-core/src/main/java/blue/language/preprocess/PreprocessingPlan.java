package blue.language.preprocess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable result of resolving and preflighting a root {@code blue}
 * directive.
 */
public final class PreprocessingPlan {

    private final String directiveBlueId;
    private final Map<String, String> effectiveImports;
    private final List<TransformationSnapshot> transformations;
    private final List<String> dependencyBlueIds;

    /**
     * Creates a frozen plan in declared transformation order.
     *
     * @param directiveBlueId exact directive identity, when reference-backed
     * @param effectiveImports immutable effective import source
     * @param transformations preflighted ordered transformations
     * @param dependencyBlueIds exact referenced dependencies in discovery order
     */
    public PreprocessingPlan(
            String directiveBlueId,
            Map<String, String> effectiveImports,
            List<TransformationSnapshot> transformations,
            List<String> dependencyBlueIds) {
        this.directiveBlueId = directiveBlueId;
        this.effectiveImports = Collections.unmodifiableMap(
                new LinkedHashMap<>(effectiveImports));
        this.transformations = Collections.unmodifiableList(
                new ArrayList<>(transformations));
        this.dependencyBlueIds = Collections.unmodifiableList(
                new ArrayList<>(dependencyBlueIds));
    }

    /**
     * Returns the referenced directive identity when the directive was not
     * inline.
     *
     * @return optional exact directive BlueId
     */
    public Optional<String> directiveBlueId() {
        return Optional.ofNullable(directiveBlueId);
    }

    /**
     * Returns all aliases available to baseline type substitution.
     *
     * @return immutable deterministic alias map
     */
    public Map<String, String> effectiveImports() {
        return effectiveImports;
    }

    /**
     * Returns preflighted transformations in exact declaration order.
     *
     * @return immutable transformation sequence
     */
    public List<TransformationSnapshot> transformations() {
        return transformations;
    }

    /**
     * Returns exact referenced dependencies in deterministic discovery order.
     *
     * @return immutable dependency identity list
     */
    public List<String> dependencyBlueIds() {
        return dependencyBlueIds;
    }
}
