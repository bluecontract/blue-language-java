package blue.language.provider;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.BlueLanguageConstants;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static blue.language.provider.ExactFragmentSupport.calculateExactBlueId;
import static blue.language.provider.ExactFragmentSupport.isPlainSchemaScalar;
import static blue.language.provider.ExactFragmentSupport.pointerPath;
import static blue.language.provider.ExactFragmentSupport.requireFinalReference;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MULTIPLE_OF;

/** Assembles a shallow fragment at every semantic child boundary. */
final class ExactFragmentAssembler {

    private final IdentityHashMap<Node, ExactFragmentSupport.FragmentRecord>
            records = new IdentityHashMap<>();
    private final IdentityHashMap<Node, String> active =
            new IdentityHashMap<>();
    private final SortedMap<String, Node> fragments = new TreeMap<>();
    private final SortedMap<String, SortedSet<String>> edges =
            new TreeMap<>();

    /** Records one exact inline node and all of its semantic children. */
    ExactFragmentSupport.FragmentRecord record(Node node, String path) {
        if (node.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Internal error: a pure reference cannot be recorded as "
                            + "exact content at " + path + ".");
        }
        ExactFragmentSupport.FragmentRecord retained = records.get(node);
        if (retained != null) {
            return retained;
        }
        String activePath = active.put(node, path);
        if (activePath != null) {
            throw new IllegalArgumentException(
                    "Blue object cycle between " + activePath + " and "
                            + path + " cannot be fragmented.");
        }
        try {
            return assemble(node, path);
        } finally {
            active.remove(node);
        }
    }

    /** Rejects reference cycles between the assembled local fragments. */
    void rejectMixedReferenceCycles() {
        ExactFragmentSupport.rejectMixedReferenceCycles(fragments, edges);
    }

    /** Returns the mutable internal result for immediate facade snapshotting. */
    Map<String, Node> fragments() {
        return fragments;
    }

    private ExactFragmentSupport.FragmentRecord assemble(
            Node node,
            String path) {
        String originalBlueId = calculateExactBlueId(node, path);
        SortedSet<String> directEdges = new TreeSet<>();
        Node direct = node.clone();

        direct.type(referenceFor(
                node.getType(),
                pointerPath(path, BlueLanguageConstants.OBJECT_TYPE),
                directEdges));
        direct.itemType(referenceFor(
                node.getItemType(),
                pointerPath(path, BlueLanguageConstants.OBJECT_ITEM_TYPE),
                directEdges));
        direct.keyType(referenceFor(
                node.getKeyType(),
                pointerPath(path, BlueLanguageConstants.OBJECT_KEY_TYPE),
                directEdges));
        direct.valueType(referenceFor(
                node.getValueType(),
                pointerPath(path, BlueLanguageConstants.OBJECT_VALUE_TYPE),
                directEdges));
        direct.contracts(referenceFor(
                node.getContracts(),
                pointerPath(path, BlueLanguageConstants.OBJECT_CONTRACTS),
                directEdges));
        direct.blue(referenceFor(
                node.getBlue(),
                pointerPath(path, BlueLanguageConstants.OBJECT_BLUE),
                directEdges));
        fragmentItems(node, direct, path, directEdges);
        fragmentProperties(node, direct, path, directEdges);
        if (node.getSchema() != null) {
            direct.schema(fragmentSchema(
                    node.getSchema(),
                    pointerPath(path, BlueLanguageConstants.OBJECT_SCHEMA),
                    directEdges));
        }
        if (node.getPreviousBlueId() != null) {
            directEdges.add(node.getPreviousBlueId());
        }

        requireStableIdentity(originalBlueId, direct, path);
        if (!fragments.containsKey(originalBlueId)) {
            fragments.put(originalBlueId, direct.clone());
        }
        edges.computeIfAbsent(
                originalBlueId,
                ignored -> new TreeSet<>())
                .addAll(directEdges);
        ExactFragmentSupport.FragmentRecord created =
                new ExactFragmentSupport.FragmentRecord(
                        originalBlueId,
                        direct);
        records.put(node, created);
        return created;
    }

    private void fragmentItems(
            Node source,
            Node direct,
            String path,
            Set<String> directEdges) {
        if (source.getItems() == null) {
            return;
        }
        List<Node> directItems = new ArrayList<>(
                source.getItems().size());
        for (int index = 0; index < source.getItems().size(); index++) {
            directItems.add(referenceFor(
                    source.getItems().get(index),
                    pointerPath(
                            pointerPath(path, BlueLanguageConstants.OBJECT_ITEMS),
                            String.valueOf(index)),
                    directEdges));
        }
        direct.items(directItems);
    }

    private void fragmentProperties(
            Node source,
            Node direct,
            String path,
            Set<String> directEdges) {
        if (source.getProperties() == null) {
            return;
        }
        Map<String, Node> directProperties = new LinkedHashMap<>();
        SortedMap<String, Node> ordered =
                new TreeMap<>(source.getProperties());
        for (Map.Entry<String, Node> property : ordered.entrySet()) {
            directProperties.put(
                    property.getKey(),
                    referenceFor(
                            property.getValue(),
                            pointerPath(path, property.getKey()),
                            directEdges));
        }
        direct.properties(directProperties);
    }

    private Node referenceFor(
            Node child,
            String path,
            Set<String> directEdges) {
        if (child == null) {
            return null;
        }
        String childBlueId = child.isReferenceOnly()
                ? requireFinalReference(
                        child.getBlueId(),
                        pointerPath(path, BlueLanguageConstants.OBJECT_BLUE_ID))
                : record(child, path).blueId;
        directEdges.add(childBlueId);
        return new Node().blueId(childBlueId);
    }

    private Schema fragmentSchema(
            Schema schema,
            String path,
            Set<String> directEdges) {
        if (schema.isReferenceOnly()) {
            String schemaBlueId = requireFinalReference(
                    schema.getBlueId(),
                    pointerPath(path, BlueLanguageConstants.OBJECT_BLUE_ID));
            directEdges.add(schemaBlueId);
            return new Schema().blueId(schemaBlueId);
        }
        Schema direct = schema.clone();
        direct.minimum(fragmentSchemaValue(
                schema.getMinimum(),
                pointerPath(path, KEY_MINIMUM),
                directEdges));
        direct.maximum(fragmentSchemaValue(
                schema.getMaximum(),
                pointerPath(path, KEY_MAXIMUM),
                directEdges));
        direct.exclusiveMinimum(fragmentSchemaValue(
                schema.getExclusiveMinimum(),
                pointerPath(path, KEY_EXCLUSIVE_MINIMUM),
                directEdges));
        direct.exclusiveMaximum(fragmentSchemaValue(
                schema.getExclusiveMaximum(),
                pointerPath(path, KEY_EXCLUSIVE_MAXIMUM),
                directEdges));
        direct.multipleOf(fragmentSchemaValue(
                schema.getMultipleOf(),
                pointerPath(path, KEY_MULTIPLE_OF),
                directEdges));
        fragmentSchemaEnum(schema, direct, path, directEdges);
        return direct;
    }

    private void fragmentSchemaEnum(
            Schema source,
            Schema direct,
            String path,
            Set<String> directEdges) {
        if (source.getEnum() == null) {
            return;
        }
        List<Node> values = new ArrayList<>(source.getEnum().size());
        for (int index = 0; index < source.getEnum().size(); index++) {
            values.add(fragmentSchemaValue(
                    source.getEnum().get(index),
                    pointerPath(
                            pointerPath(path, KEY_ENUM),
                            String.valueOf(index)),
                    directEdges));
        }
        direct.enumValues(values);
    }

    /*
     * Plain count/boolean/numeric schema wrappers hash as scalar values and
     * remain inline. Decorated wrappers are ordinary semantic child nodes.
     */
    private Node fragmentSchemaValue(
            Node value,
            String path,
            Set<String> directEdges) {
        if (value == null) {
            return null;
        }
        return isPlainSchemaScalar(value)
                ? value.clone()
                : referenceFor(value, path, directEdges);
    }

    private void requireStableIdentity(
            String originalBlueId,
            Node direct,
            String path) {
        String directBlueId = calculateExactBlueId(direct, path);
        if (!originalBlueId.equals(directBlueId)) {
            throw new IllegalStateException(
                    "Shallow fragmentation changed BlueId at " + path
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
