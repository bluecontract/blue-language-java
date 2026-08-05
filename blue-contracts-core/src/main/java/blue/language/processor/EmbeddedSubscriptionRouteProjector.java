package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.util.ProcessorContractConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates Process Embedded path declarations and projects absolute routes.
 */
final class EmbeddedSubscriptionRouteProjector {

    private final SubscriptionSurfaceRules rules;

    EmbeddedSubscriptionRouteProjector(SubscriptionSurfaceRules rules) {
        this.rules = rules;
    }

    /**
     * Projects one scope through either its frozen entry plan or a newly
     * validated tentative plan.
     */
    List<String> projectScope(
            Node effectiveScope,
            EmbeddedScopeDeclaration declaration,
            EmbeddedScopePlan frozenEntryPlan,
            String scopePath,
            GasSchedule schedule,
            EmbeddedScopePlanner planner) {
        EmbeddedScopePlan plan = frozenEntryPlan != null
                ? frozenEntryPlan
                : planner.planForRevisionBoundEvent(
                        effectiveScope,
                        scopePath,
                        declaration.explicitPaths(),
                        declaration.collectionPaths(),
                        schedule);
        if (!ProcessorEngine.normalizeScope(scopePath)
                .equals(plan.scopePath())) {
            throw rules.invalid(
                    "Embedded entry plan belongs to another scope",
                    scopePath,
                    null);
        }
        return plan.concreteChildPaths();
    }

    /** Reads independent exact and collection declarations from a marker. */
    EmbeddedScopeDeclaration declaration(
            Node embedded,
            String scopePath,
            String key) {
        return EmbeddedScopeDeclaration.of(
                textList(
                        rules.property(
                                embedded,
                                ProcessorContractConstants.KEY_PATHS),
                        ProcessorContractConstants.KEY_PATHS,
                        scopePath,
                        key),
                textList(
                        rules.property(
                                embedded,
                                ProcessorContractConstants
                                        .KEY_COLLECTION_PATHS),
                        ProcessorContractConstants.KEY_COLLECTION_PATHS,
                        scopePath,
                        key));
    }

    private List<String> textList(
            Node list,
            String field,
            String scopePath,
            String key) {
        if (list == null || Nodes.isEmptyNode(list)) {
            return Collections.emptyList();
        }
        if (list.getItems() == null) {
            throw rules.invalid(
                    "Process Embedded " + field + " must be a finite List",
                    scopePath,
                    key);
        }
        List<String> values = new ArrayList<>(list.getItems().size());
        for (Node item : list.getItems()) {
            Object value = item != null ? item.getValue() : null;
            if (!(value instanceof String)) {
                throw rules.invalid(
                        "Process Embedded " + field
                                + " entry must be Text",
                        scopePath,
                        key);
            }
            values.add((String) value);
        }
        return values;
    }
}
