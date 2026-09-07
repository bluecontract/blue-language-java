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
        this.rules = new SubscriptionSurfaceRules(snapshotManager);
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
        return project(
                root,
                snapshot,
                schedule,
                changedPaths,
                context,
                EmbeddedMembership.TENTATIVE);
    }

    /** Projects the current-event surface from frozen entry membership. */
    Map<String, SubscriptionDelta.Entry> projectEntry(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            Set<String> changedPaths,
            SubscriptionSurfaceValidationContext context) {
        return project(
                root,
                snapshot,
                schedule,
                changedPaths,
                context,
                EmbeddedMembership.ENTRY);
    }

    /** Projects the post-commit candidate from tentative final membership. */
    Map<String, SubscriptionDelta.Entry> projectTentative(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            Set<String> changedPaths,
            SubscriptionSurfaceValidationContext context) {
        return project(
                root,
                snapshot,
                schedule,
                changedPaths,
                context,
                EmbeddedMembership.TENTATIVE);
    }

    /** Captures the complete current-event contract surface. */
    void captureCompleteEntry(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            SubscriptionSurfaceValidationContext context,
            ContractSurfaceCollector collector) {
        captureComplete(
                root,
                snapshot,
                schedule,
                context,
                EmbeddedMembership.ENTRY,
                collector);
    }

    /** Captures the complete post-commit candidate contract surface. */
    void captureCompleteTentative(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            SubscriptionSurfaceValidationContext context,
            ContractSurfaceCollector collector) {
        captureComplete(
                root,
                snapshot,
                schedule,
                context,
                EmbeddedMembership.TENTATIVE,
                collector);
    }

    private void captureComplete(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            SubscriptionSurfaceValidationContext context,
            EmbeddedMembership membership,
            ContractSurfaceCollector collector) {
        if (effective == null) {
            throw new IllegalStateException(
                    "Complete contract-surface capture requires configured "
                            + "effective contract traversal");
        }
        effective.captureComplete(
                root,
                snapshot,
                schedule,
                context,
                membership,
                collector);
    }

    private Map<String, SubscriptionDelta.Entry> project(
            Node root,
            ResolvedSnapshot snapshot,
            GasSchedule schedule,
            Set<String> changedPaths,
            SubscriptionSurfaceValidationContext context,
            EmbeddedMembership membership) {
        if (effective != null) {
            return effective.project(
                    root,
                    snapshot,
                    schedule,
                    changedPaths,
                    context,
                    membership);
        }
        return direct.project(
                root,
                schedule,
                changedPaths,
                context,
                membership);
    }

    /** Shares the stateless rules with interval validation. */
    SubscriptionSurfaceRules rules() {
        return rules;
    }

    /** Selects the immutable membership snapshot used for route projection. */
    enum EmbeddedMembership {
        /** Current event's write-once entry membership. */
        ENTRY,
        /** Tentative final membership that becomes active after commit. */
        TENTATIVE
    }
}
