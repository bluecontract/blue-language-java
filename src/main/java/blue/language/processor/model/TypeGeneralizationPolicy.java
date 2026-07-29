package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.List;

/**
 * Scope-level policy defining the default type-generalization mode and
 * path-specific overrides.
 *
 * <p>This is a mutable wire model. The rules list is retained and returned by
 * reference rather than defensively copied.</p>
 */
@TypeBlueId(RuntimeBlueIds.TYPE_GENERALIZATION_POLICY)
public class TypeGeneralizationPolicy extends MarkerContract {

    private String defaultMode;
    private List<TypeGeneralizationRule> rules;

    /** Creates an empty type-generalization policy. */
    public TypeGeneralizationPolicy() {
    }

    /**
     * Returns the fallback mode used when no path-specific rule matches.
     *
     * @return default mode, or {@code null} when no mode is configured
     */
    public String getDefaultMode() {
        return defaultMode;
    }

    /**
     * Sets the fallback mode used when no path-specific rule matches.
     *
     * @param defaultMode fallback mode, or {@code null} to clear it
     */
    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    /**
     * Returns the path-specific rules in declaration order.
     *
     * @return retained mutable rules reference, or {@code null} when absent
     */
    public List<TypeGeneralizationRule> getRules() {
        return rules;
    }

    /**
     * Replaces the path-specific rules evaluated in declaration order.
     *
     * @param rules rules retained by reference, or {@code null}
     */
    public void setRules(List<TypeGeneralizationRule> rules) {
        this.rules = rules;
    }
}
