package blue.language.provider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.Properties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static blue.language.provider.ExactFragmentSupport.calculateExactBlueId;
import static blue.language.provider.ExactFragmentSupport.collectReferenceIds;
import static blue.language.provider.ExactFragmentSupport.isPlainSchemaScalar;
import static blue.language.provider.ExactFragmentSupport.pointerPath;
import static blue.language.provider.ExactFragmentSupport.requireItemIndex;
import static blue.language.utils.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MULTIPLE_OF;
import static blue.language.utils.SchemaPropertyConstants.KEY_REQUIRED;
import static blue.language.utils.SchemaPropertyConstants.KEY_UNIQUE_ITEMS;

/** Assembles exact fragments only along selected root-to-cut paths. */
final class SelectiveExactFragmentAssembler {

    private final ExactFragmentSupport.CutSelection rootSelection;
    private final SortedMap<String, Node> fragments = new TreeMap<>();
    private final SortedMap<String, SortedSet<String>> edges =
            new TreeMap<>();

    SelectiveExactFragmentAssembler(
            ExactFragmentSupport.CutSelection rootSelection) {
        this.rootSelection = rootSelection;
    }

    /** Records the selected cut graph rooted at one exact inline node. */
    ExactFragmentSupport.FragmentRecord record(Node node, String path) {
        return record(node, rootSelection, path);
    }

    /** Rejects reference cycles between the assembled local fragments. */
    void rejectMixedReferenceCycles() {
        ExactFragmentSupport.rejectMixedReferenceCycles(fragments, edges);
    }

    /** Returns the mutable internal result for immediate facade snapshotting. */
    Map<String, Node> fragments() {
        return fragments;
    }

    private ExactFragmentSupport.FragmentRecord record(
            Node node,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        if (node == null || node.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "A selected exact fragment cut requires inline Node "
                            + "content at " + path + ".");
        }
        String originalBlueId = calculateExactBlueId(node, path);
        Node direct = node.clone();
        for (Map.Entry<String, ExactFragmentSupport.CutSelection> child
                : selection.children.entrySet()) {
            applyCut(
                    node,
                    direct,
                    child.getKey(),
                    child.getValue(),
                    path);
        }
        requireStableIdentity(originalBlueId, direct, path);
        if (!fragments.containsKey(originalBlueId)) {
            fragments.put(originalBlueId, direct.clone());
            SortedSet<String> referenced = new TreeSet<>();
            collectReferenceIds(
                    direct,
                    referenced,
                    Collections.newSetFromMap(
                            new IdentityHashMap<Node, Boolean>()));
            edges.put(originalBlueId, referenced);
        }
        return new ExactFragmentSupport.FragmentRecord(
                originalBlueId,
                direct);
    }

    private void applyCut(
            Node source,
            Node direct,
            String segment,
            ExactFragmentSupport.CutSelection selection,
            String parentPath) {
        String path = pointerPath(parentPath, segment);
        switch (segment) {
            case Properties.OBJECT_TYPE:
                direct.type(fragmentReference(
                        source.getType(), selection, path));
                return;
            case Properties.OBJECT_ITEM_TYPE:
                direct.itemType(fragmentReference(
                        source.getItemType(), selection, path));
                return;
            case Properties.OBJECT_KEY_TYPE:
                direct.keyType(fragmentReference(
                        source.getKeyType(), selection, path));
                return;
            case Properties.OBJECT_VALUE_TYPE:
                direct.valueType(fragmentReference(
                        source.getValueType(), selection, path));
                return;
            case Properties.OBJECT_CONTRACTS:
                direct.contracts(fragmentReference(
                        source.getContracts(), selection, path));
                return;
            case Properties.OBJECT_BLUE:
                direct.blue(fragmentReference(
                        source.getBlue(), selection, path));
                return;
            case Properties.OBJECT_SCHEMA:
                applySchemaCuts(
                        source.getSchema(),
                        direct.getSchema(),
                        selection,
                        path);
                return;
            case Properties.OBJECT_ITEMS:
                applyItemCuts(source, direct, selection, path);
                return;
            default:
                break;
        }
        if (source.getItems() != null) {
            int index = requireItemIndex(
                    segment,
                    source.getItems().size(),
                    path);
            Node child = source.getItems().get(index);
            direct.getItems().set(
                    index,
                    fragmentReference(child, selection, path));
            return;
        }
        Map<String, Node> properties = source.getProperties();
        if (properties == null || !properties.containsKey(segment)) {
            throw new IllegalArgumentException(
                    "Exact graph fragment cut does not select a Node at "
                            + path + ".");
        }
        direct.getProperties().put(
                segment,
                fragmentReference(
                        properties.get(segment),
                        selection,
                        path));
    }

    private void applyItemCuts(
            Node source,
            Node direct,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        if (selection.selected) {
            throw new IllegalArgumentException(
                    "The list items container is not an ordinary Node "
                            + "fragment at " + path + ".");
        }
        if (source.getItems() == null) {
            throw new IllegalArgumentException(
                    "Exact graph fragment cut does not select list items at "
                            + path + ".");
        }
        for (Map.Entry<String, ExactFragmentSupport.CutSelection> item
                : selection.children.entrySet()) {
            String itemPath = pointerPath(path, item.getKey());
            int index = requireItemIndex(
                    item.getKey(),
                    source.getItems().size(),
                    itemPath);
            direct.getItems().set(
                    index,
                    fragmentReference(
                            source.getItems().get(index),
                            item.getValue(),
                            itemPath));
        }
    }

    private void applySchemaCuts(
            Schema source,
            Schema direct,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        if (selection.selected) {
            throw new IllegalArgumentException(
                    "An inline schema container is not an ordinary Node "
                            + "fragment at " + path + ".");
        }
        if (source == null || direct == null || source.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Exact graph fragment cut cannot traverse schema at "
                            + path + ".");
        }
        for (Map.Entry<String, ExactFragmentSupport.CutSelection> keyword
                : selection.children.entrySet()) {
            applySchemaKeyword(
                    source,
                    direct,
                    keyword.getKey(),
                    keyword.getValue(),
                    pointerPath(path, keyword.getKey()));
        }
    }

    private void applySchemaKeyword(
            Schema source,
            Schema direct,
            String keyword,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        switch (keyword) {
            case KEY_REQUIRED:
                direct.required(fragmentSchemaReference(
                        source.getRequired(), selection, path));
                return;
            case KEY_MIN_LENGTH:
                direct.minLength(fragmentSchemaReference(
                        source.getMinLength(), selection, path));
                return;
            case KEY_MAX_LENGTH:
                direct.maxLength(fragmentSchemaReference(
                        source.getMaxLength(), selection, path));
                return;
            case KEY_MINIMUM:
                direct.minimum(fragmentSchemaReference(
                        source.getMinimum(), selection, path));
                return;
            case KEY_MAXIMUM:
                direct.maximum(fragmentSchemaReference(
                        source.getMaximum(), selection, path));
                return;
            case KEY_EXCLUSIVE_MINIMUM:
                direct.exclusiveMinimum(fragmentSchemaReference(
                        source.getExclusiveMinimum(), selection, path));
                return;
            case KEY_EXCLUSIVE_MAXIMUM:
                direct.exclusiveMaximum(fragmentSchemaReference(
                        source.getExclusiveMaximum(), selection, path));
                return;
            case KEY_MULTIPLE_OF:
                direct.multipleOf(fragmentSchemaReference(
                        source.getMultipleOf(), selection, path));
                return;
            case KEY_MIN_ITEMS:
                direct.minItems(fragmentSchemaReference(
                        source.getMinItems(), selection, path));
                return;
            case KEY_MAX_ITEMS:
                direct.maxItems(fragmentSchemaReference(
                        source.getMaxItems(), selection, path));
                return;
            case KEY_UNIQUE_ITEMS:
                direct.uniqueItems(fragmentSchemaReference(
                        source.getUniqueItems(), selection, path));
                return;
            case KEY_MIN_FIELDS:
                direct.minFields(fragmentSchemaReference(
                        source.getMinFields(), selection, path));
                return;
            case KEY_MAX_FIELDS:
                direct.maxFields(fragmentSchemaReference(
                        source.getMaxFields(), selection, path));
                return;
            case KEY_ENUM:
                applySchemaEnumCuts(source, direct, selection, path);
                return;
            default:
                throw new IllegalArgumentException(
                        "Unknown schema cut segment at " + path + ".");
        }
    }

    private void applySchemaEnumCuts(
            Schema source,
            Schema direct,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        if (selection.selected) {
            throw new IllegalArgumentException(
                    "The schema enum container is not an ordinary Node "
                            + "fragment at " + path + ".");
        }
        if (source.getEnum() == null) {
            throw new IllegalArgumentException(
                    "Exact graph fragment cut does not select schema enum "
                            + "content at " + path + ".");
        }
        List<Node> values = new ArrayList<>(direct.getEnum());
        for (Map.Entry<String, ExactFragmentSupport.CutSelection> value
                : selection.children.entrySet()) {
            String valuePath = pointerPath(path, value.getKey());
            int index = requireItemIndex(
                    value.getKey(),
                    source.getEnum().size(),
                    valuePath);
            values.set(
                    index,
                    fragmentSchemaReference(
                            source.getEnum().get(index),
                            value.getValue(),
                            valuePath));
        }
        direct.enumValues(values);
    }

    private Node fragmentSchemaReference(
            Node child,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        if (child == null || isPlainSchemaScalar(child)) {
            throw new IllegalArgumentException(
                    "A scalar schema value is not an ordinary Node "
                            + "fragment at " + path + ".");
        }
        return fragmentReference(child, selection, path);
    }

    private Node fragmentReference(
            Node child,
            ExactFragmentSupport.CutSelection selection,
            String path) {
        if (child == null || child.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "A selected exact fragment cut requires inline Node "
                            + "content at " + path + ".");
        }
        return new Node().blueId(record(child, selection, path).blueId);
    }

    private void requireStableIdentity(
            String originalBlueId,
            Node direct,
            String path) {
        String directBlueId = calculateExactBlueId(direct, path);
        if (!originalBlueId.equals(directBlueId)) {
            throw new IllegalStateException(
                    "Selective fragmentation changed BlueId at " + path
                            + " from " + originalBlueId + " to "
                            + directBlueId + ".");
        }
        if (direct.getBlueId() != null) {
            throw new IllegalStateException(
                    "A fragment must not contain its own BlueId at "
                            + path + ".");
        }
    }
}
