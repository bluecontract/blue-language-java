package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.List;

@TypeBlueId(RuntimeBlueIds.TYPE_GENERALIZATION_POLICY)
public class TypeGeneralizationPolicy extends MarkerContract {

    private String defaultMode;
    private List<TypeGeneralizationRule> rules;

    public String getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    public List<TypeGeneralizationRule> getRules() {
        return rules;
    }

    public void setRules(List<TypeGeneralizationRule> rules) {
        this.rules = rules;
    }
}
