package blue.language.provider;

import blue.language.api.BlueViewPath;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.BlueLanguageConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

/** Shared deterministic operations for exact-fragment collaborators. */
final class ExactFragmentSupport {

    private ExactFragmentSupport() {
    }

    /** Returns a defensive, lexically ordered immutable fragment snapshot. */
    static SortedMap<String, Node> immutableFragmentSnapshot(
            Map<String, Node> source) {
        SortedMap<String, Node> snapshot = new TreeMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableSortedMap(snapshot);
    }

    /** Parses authored RFC 6901 cuts into one canonical selection tree. */
    static CutSelection cutSelection(Collection<String> cuts) {
        CutSelection root = new CutSelection();
        for (String cut : cuts) {
            if (cut == null) {
                throw new IllegalArgumentException(
                        "Exact graph fragment cut must not be null.");
            }
            CutSelection cursor = root;
            for (String segment : BlueViewPath.split(cut)) {
                cursor = cursor.children.computeIfAbsent(
                        segment,
                        ignored -> new CutSelection());
            }
            cursor.selected = true;
        }
        return root;
    }

    /** Collects every direct or nested BlueId reference from a node. */
    static void collectReferenceIds(
            Node node,
            Set<String> references,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.isReferenceOnly()) {
            references.add(node.getBlueId());
            return;
        }
        collectReferenceIds(node.getType(), references, visited);
        collectReferenceIds(node.getItemType(), references, visited);
        collectReferenceIds(node.getKeyType(), references, visited);
        collectReferenceIds(node.getValueType(), references, visited);
        collectReferenceIds(node.getContracts(), references, visited);
        collectReferenceIds(node.getBlue(), references, visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                collectReferenceIds(item, references, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                collectReferenceIds(property, references, visited);
            }
        }
        collectReferenceIds(node.getSchema(), references, visited);
        if (node.getPreviousBlueId() != null) {
            references.add(node.getPreviousBlueId());
        }
    }

    private static void collectReferenceIds(
            Schema schema,
            Set<String> references,
            Set<Node> visited) {
        if (schema == null) {
            return;
        }
        if (schema.isReferenceOnly()) {
            references.add(schema.getBlueId());
            return;
        }
        collectReferenceIds(schema.getRequired(), references, visited);
        collectReferenceIds(schema.getMinLength(), references, visited);
        collectReferenceIds(schema.getMaxLength(), references, visited);
        collectReferenceIds(schema.getMinimum(), references, visited);
        collectReferenceIds(schema.getMaximum(), references, visited);
        collectReferenceIds(
                schema.getExclusiveMinimum(), references, visited);
        collectReferenceIds(
                schema.getExclusiveMaximum(), references, visited);
        collectReferenceIds(schema.getMultipleOf(), references, visited);
        collectReferenceIds(schema.getMinItems(), references, visited);
        collectReferenceIds(schema.getMaxItems(), references, visited);
        collectReferenceIds(schema.getUniqueItems(), references, visited);
        collectReferenceIds(schema.getMinFields(), references, visited);
        collectReferenceIds(schema.getMaxFields(), references, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                collectReferenceIds(value, references, visited);
            }
        }
    }

    /** Validates and resolves a canonical list-index path segment. */
    static int requireItemIndex(
            String segment,
            int size,
            String path) {
        if (segment == null || segment.isEmpty()
                || (segment.length() > 1 && segment.charAt(0) == '0')) {
            throw new IllegalArgumentException(
                    "Exact graph fragment list cut requires a canonical "
                            + "array index at " + path + ".");
        }
        for (int index = 0; index < segment.length(); index++) {
            char digit = segment.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException(
                        "Exact graph fragment list cut requires a canonical "
                                + "array index at " + path + ".");
            }
        }
        final int index;
        try {
            index = Integer.parseInt(segment);
        } catch (NumberFormatException tooLarge) {
            throw new IllegalArgumentException(
                    "Exact graph fragment list index is outside the "
                            + "supported range at " + path + ".",
                    tooLarge);
        }
        if (index >= size) {
            throw new IllegalArgumentException(
                    "Exact graph fragment list index is absent at "
                            + path + ".");
        }
        return index;
    }

    /** Appends one escaped JSON-pointer segment to an evidence path. */
    static String pointerPath(String parent, String segment) {
        return JsonPointer.append(parent, segment);
    }

    /** Requires a final plain or finalized cyclic-member reference. */
    static String requireFinalReference(String blueId, String path) {
        return BlueIds.requireBlueIdOrCyclicMember(
                BlueIds.requireNoThisPlaceholderOutsideCyclicApi(
                        blueId,
                        path),
                path);
    }

    /** Detects schema wrappers whose scalar value must remain inline. */
    static boolean isPlainSchemaScalar(Node node) {
        return node != null
                && node.getRawValue() != null
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    /** Calculates one exact ordinary identity with path-local diagnostics. */
    static String calculateExactBlueId(Node node, String path) {
        try {
            return DirectBlueIdCalculator.calculateBlueId(node);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid exact ordinary Blue content at " + path + ".",
                    invalid);
        }
    }

    /** Rejects cycles formed by references between locally stored fragments. */
    static void rejectMixedReferenceCycles(
            Map<String, Node> fragments,
            Map<String, SortedSet<String>> edges) {
        Map<String, VisitState> states = new TreeMap<>();
        for (String blueId : fragments.keySet()) {
            rejectMixedReferenceCycles(
                    blueId,
                    fragments,
                    edges,
                    states,
                    new ArrayList<String>());
        }
    }

    private static void rejectMixedReferenceCycles(
            String blueId,
            Map<String, Node> fragments,
            Map<String, SortedSet<String>> edges,
            Map<String, VisitState> states,
            List<String> path) {
        VisitState state = states.get(blueId);
        if (state == VisitState.COMPLETE) {
            return;
        }
        if (state == VisitState.ACTIVE) {
            path.add(blueId);
            throw new IllegalArgumentException(
                    "Mixed reference/object cycle cannot be fragmented: "
                            + path
                            + ". Cyclic sets require cyclic-aware proof.");
        }
        states.put(blueId, VisitState.ACTIVE);
        path.add(blueId);
        SortedSet<String> targets = edges.get(blueId);
        if (targets != null) {
            for (String target : targets) {
                if (fragments.containsKey(target)) {
                    rejectMixedReferenceCycles(
                            target,
                            fragments,
                            edges,
                            states,
                            new ArrayList<>(path));
                }
            }
        }
        states.put(blueId, VisitState.COMPLETE);
    }

    /** Canonical cut-selection tree. */
    static final class CutSelection {
        final SortedMap<String, CutSelection> children = new TreeMap<>();
        boolean selected;
    }

    /** Exact identity plus defensive direct-fragment representation. */
    static final class FragmentRecord {
        final String blueId;
        final Node directFragment;

        FragmentRecord(String blueId, Node directFragment) {
            this.blueId = blueId;
            this.directFragment = directFragment.clone();
        }
    }

    private enum VisitState {
        ACTIVE,
        COMPLETE
    }
}
