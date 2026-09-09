package blue.language.processor.closure;

import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.snapshot.FrozenNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Proposed closed exact-reference receipt proof; it confers no publication authority. */
final class RootedCheckpointReferenceGraph {
    private RootedCheckpointReferenceGraph() { }

    static boolean verifies(AffectedClosureSnapshot before, AffectedClosureSnapshot after,
            List<GraphChange> changes) {
        if (!sameTopology(before, after)) return false;
        Map<String, ManagedOccurrenceBinding> next = rows(after);
        List<ManagedOccurrenceBinding> expected = new ArrayList<>();
        for (ManagedOccurrenceBinding old : before.occurrences()) {
            ManagedOccurrenceBinding current = next.get(old.occurrenceIdentity());
            if (!authenticates(before, old) || !authenticates(after, current)) return false;
            if (old.active() && !old.expectedTargetBlueId().equals(current.expectedTargetBlueId()))
                expected.add(old);
        }
        expected.sort((left, right) -> {
            int order = left.sourceDocumentId().compareTo(right.sourceDocumentId());
            return order == 0 ? ClosureValueSupport.comparePortableText(left.sourcePath(), right.sourcePath()) : order;
        });
        if (changes.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            ManagedOccurrenceBinding old = expected.get(index);
            ManagedOccurrenceBinding current = next.get(old.occurrenceIdentity());
            GraphChange receipt = changes.get(index);
            if (receipt.graphChangeOrdinal() != index || receipt.changeKind() != GraphChange.Kind.REBIND
                    || !receipt.sourceDocumentId().equals(old.sourceDocumentId())
                    || !receipt.sourcePath().equals(old.sourcePath())
                    || !matches(receipt.before(), old) || !matches(receipt.after(), current)) return false;
        }
        return true;
    }

    // Apply to every actual boundary as well as the final result, so a temporary
    // ADD/REMOVE/retarget/cursor change cannot disappear in the final projection.
    static boolean sameTopology(AffectedClosureSnapshot before, AffectedClosureSnapshot after) {
        Map<String, ManagedOccurrenceBinding> previous = rows(before), next = rows(after);
        if (previous == null || next == null || !previous.keySet().equals(next.keySet())
                || !before.graph().documentIds().equals(after.graph().documentIds())
                || !before.graph().adjacency().equals(after.graph().adjacency())
                || !before.graph().immutableSources().equals(after.graph().immutableSources())
                || !before.publicRootDocumentIds().equals(after.publicRootDocumentIds())) return false;
        for (Map.Entry<String, ManagedOccurrenceBinding> item : previous.entrySet()) {
            ManagedOccurrenceBinding old = item.getValue(), current = next.get(item.getKey());
            if (!current.sourceDocumentId().equals(old.sourceDocumentId())
                    || !current.sourcePath().equals(old.sourcePath())
                    || !current.targetDocumentId().equals(old.targetDocumentId())
                    || !current.bindingPolicyIdentity().equals(old.bindingPolicyIdentity())
                    || current.activationGeneration() != old.activationGeneration()
                    || current.active() != old.active()
                    || !Objects.equals(current.pendingHistoricalEpoch(), old.pendingHistoricalEpoch())
                    || !Objects.equals(current.pendingRepresentationCursor(), old.pendingRepresentationCursor())) return false;
        }
        // Reuse the ordinary active-occurrence transition law. Do not manufacture
        // a generation exception for a nonempty receipt array.
        if (after.graphGeneration() != ClosureGraphGenerationTransition.assign(
                before.graphGeneration(), before.graph(), after.graph())) return false;
        Map<String, ComponentSnapshot> components = new HashMap<>();
        for (ComponentSnapshot component : after.components())
            if (components.put(component.componentIdentity(), component) != null) return false;
        if (components.size() != before.components().size()) return false;
        for (ComponentSnapshot old : before.components()) {
            ComponentSnapshot current = components.remove(old.componentIdentity());
            if (current == null || current.kind() != old.kind()
                    || current.componentGeneration() != old.componentGeneration()
                    || !current.orderedMemberDocumentIds().equals(old.orderedMemberDocumentIds())) return false;
        }
        return components.isEmpty();
    }

    private static Map<String, ManagedOccurrenceBinding> rows(AffectedClosureSnapshot snapshot) {
        Map<String, ManagedOccurrenceBinding> result = new HashMap<>();
        for (ManagedOccurrenceBinding row : snapshot.occurrences()) {
            if (result.put(row.occurrenceIdentity(), row) != null) return null;
            ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
            if (!row.occurrenceIdentity().equals(identities.managedOccurrenceIdentity(
                    row.sourceDocumentId(), row.sourceAddress(), row.targetDocumentId(), row.bindingPolicyIdentity()))
                    || !row.bindingIdentity().equals(identities.managedOccurrenceBindingIdentity(
                    row.sourceDocumentId(), row.sourceAddress(), row.targetDocumentId(), row.expectedTargetBlueId(),
                    row.bindingPolicyIdentity()))) return null;
        }
        return result;
    }

    private static boolean authenticates(AffectedClosureSnapshot snapshot, ManagedOccurrenceBinding row) {
        if (!row.active() && row.pendingHistoricalEpoch() == null) {
            ManagedDocumentSnapshot target = snapshot.managedDocument(row.targetDocumentId());
            return target != null && exact(target.document()).equals(row.expectedTargetBlueId());
        }
        ManagedDocumentSnapshot source = snapshot.managedDocument(row.sourceDocumentId());
        Node value = source == null ? null : NodePathEditor.getOrNull(source.document(), row.sourcePath());
        return value != null && exact(value).equals(row.expectedTargetBlueId());
    }

    private static boolean matches(GraphChange.Side side, ManagedOccurrenceBinding row) {
        return side != null && side.activationGeneration() == row.activationGeneration()
                && side.occurrenceIdentity().equals(row.occurrenceIdentity())
                && side.bindingIdentity().equals(row.bindingIdentity())
                && side.targetDocumentId().equals(row.targetDocumentId())
                && side.targetBlueId().equals(row.expectedTargetBlueId());
    }

    private static String exact(Node node) {
        return FrozenNode.fromNode(NodeToBlueIdInput.stripResolvedBlueIdMetadata(node)).blueId();
    }
}
