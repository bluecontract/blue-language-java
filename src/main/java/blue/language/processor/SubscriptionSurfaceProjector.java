package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically projects the changed, indexable subscription surface.
 *
 * <p>The projector selects either direct materialized traversal or effective
 * snapshot traversal from immutable construction-time dependencies. It does
 * not compare revisions, bind activation intervals, or persist an index.</p>
 */
final class SubscriptionSurfaceProjector {

    private final SubscriptionSurfaceRules rules;
    private final DirectSubscriptionSurfaceProjector direct;
    private final EffectiveSubscriptionSurfaceProjector effective;

    SubscriptionSurfaceProjector(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter) {
        this.rules = new SubscriptionSurfaceRules();
        this.direct = new DirectSubscriptionSurfaceProjector(rules);
        this.effective = contractLoader != null && registry != null
                ? new EffectiveSubscriptionSurfaceProjector(
                        contractLoader,
                        snapshotManager,
                        registry,
                        converter,
                        rules)
                : null;
    }

    /** Validates and freezes changed pointers in deterministic order. */
    Set<String> normalizeChangedPaths(Set<String> changedPaths) {
        return rules.normalizeChanges(
                Objects.requireNonNull(changedPaths, "changedPaths"));
    }

    /** Projects the changed surface for one exact selected Root. */
    Map<String, SubscriptionDelta.Entry> project(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            Set<String> changedPaths,
            SubscriptionSurfaceValidationContext context) {
        if (effective != null) {
            return effective.project(
                    root, snapshot, schedule, changedPaths, context);
        }
        return direct.project(root, schedule, changedPaths);
    }

    /** Shares the stateless rules with interval validation. */
    SubscriptionSurfaceRules rules() {
        return rules;
    }
}
