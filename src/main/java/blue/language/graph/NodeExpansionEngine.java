package blue.language.graph;

import blue.language.BlueOperationLimits;
import blue.language.BlueOperationOutcome;
import blue.language.BlueOperationResult;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.model.Schema;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.Properties;
import blue.language.utils.SchemaToMapListOrValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Performs exact reference expansion without applying resolution semantics.
 *
 * <p>The engine owns no cache, configuration, or lifecycle state. Its provider
 * is selected by the surrounding runtime before an operation begins, so a
 * configuration generation cannot change during recursive traversal.</p>
 */
final class NodeExpansionEngine {

    private final NodeProvider nodeProvider;

    NodeExpansionEngine(NodeProvider nodeProvider) {
        this.nodeProvider = Objects.requireNonNull(
                nodeProvider, "nodeProvider");
    }

    /** Expands every reachable exact reference into an independent graph. */
    Node expand(Node source) {
        if (source == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return expandReferences(source);
    }

    /** Expands only the semantic closure selected by {@code limits}. */
    BlueOperationResult<Node> expandLimited(
            Node source, BlueOperationLimits limits) {
        Objects.requireNonNull(source, "node");
        Objects.requireNonNull(limits, "limits");
        LimitedExpansionContext context = new LimitedExpansionContext(
                limits.maxReferenceExpansions());
        Node expanded = source.clone();
        boolean anyEstablished = false;
        boolean anyAbsent = false;
        for (List<String> demand : demandedSegments(limits)) {
            DemandExpansion result = expandDemand(
                    expanded, demand, 0, context);
            expanded = result.node;
            if (result.outcome == BlueOperationOutcome.INVALID) {
                return BlueOperationResult.invalid(
                        result.reason,
                        context.providerOutcome == null
                                ? NodeProviderOutcome.INVALID_EVIDENCE
                                : context.providerOutcome);
            }
            if (result.outcome == BlueOperationOutcome.INCOMPLETE) {
                return BlueOperationResult.incomplete(
                        expanded,
                        context.outstandingBlueIds,
                        context.providerOutcome,
                        result.reason);
            }
            anyEstablished |= result.outcome
                    == BlueOperationOutcome.ESTABLISHED;
            anyAbsent |= result.outcome
                    == BlueOperationOutcome.ABSENT;
        }
        if (!anyEstablished && anyAbsent) {
            return BlueOperationResult.absent(
                    "Every demanded path is semantically absent.");
        }
        return BlueOperationResult.established(expanded);
    }

    private List<List<String>> demandedSegments(
            BlueOperationLimits limits) {
        List<List<String>> result = new ArrayList<>(
                limits.demandedPaths().size());
        for (String path : limits.demandedPaths()) {
            result.add(JsonPointer.split(path));
        }
        return result;
    }

    private Node expandReferences(Node node) {
        if (node == null) {
            return null;
        }
        if (node.isReferenceOnly()) {
            List<Node> nodes = nodeProvider.fetchByBlueId(
                    node.getBlueId());
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "No content found for blueId: "
                                + node.getBlueId());
            }
            if (nodes.size() == 1) {
                return expandReferences(
                        providerContentWithoutRootIdentity(
                                nodes.get(0)));
            }
            return new Node().items(expandReferences(
                    providerContentWithoutRootIdentity(nodes)));
        }

        Node expanded = node.clone();
        expanded.type(expandReferences(expanded.getType()));
        expanded.itemType(expandReferences(expanded.getItemType()));
        expanded.keyType(expandReferences(expanded.getKeyType()));
        expanded.valueType(expandReferences(expanded.getValueType()));
        expanded.blue(expandReferences(expanded.getBlue()));
        expanded.contracts(expandReferences(expanded.getContracts()));
        if (expanded.getItems() != null) {
            expanded.items(expandReferences(expanded.getItems()));
        }
        if (expanded.getProperties() != null) {
            Map<String, Node> expandedProperties =
                    new LinkedHashMap<>();
            expanded.getProperties().forEach((key, value) ->
                    expandedProperties.put(
                            key, expandReferences(value)));
            expanded.properties(expandedProperties);
        }
        if (expanded.getSchema() != null) {
            expanded.schema(expandReferences(
                    expanded.getSchema()));
        }
        return expanded;
    }

    private DemandExpansion expandDemand(
            Node node,
            List<String> segments,
            int index,
            LimitedExpansionContext context) {
        Node current = node;
        if (current != null && current.isReferenceOnly()) {
            String blueId = current.getBlueId();
            if (!context.tryAcquire(blueId)) {
                return DemandExpansion.incomplete(
                        current,
                        "Reference expansion limit reached for "
                                + blueId + ".");
            }
            NodeProviderResult providerResult =
                    nodeProvider.fetchResultByBlueId(blueId);
            context.providerOutcome = providerResult.outcome();
            if (providerResult.outcome()
                    == NodeProviderOutcome.UNAVAILABLE
                    || providerResult.outcome()
                    == NodeProviderOutcome.NOT_FOUND) {
                context.outstandingBlueIds.add(blueId);
                return DemandExpansion.incomplete(
                        current,
                        providerResult.diagnostic().orElse(
                                "Required provider evidence was not "
                                        + "available for " + blueId + "."));
            }
            if (providerResult.outcome()
                    == NodeProviderOutcome.INVALID_EVIDENCE) {
                return DemandExpansion.invalid(
                        current,
                        providerResult.diagnostic().orElse(
                                "Provider returned invalid evidence for "
                                        + blueId + "."));
            }
            List<Node> nodes = providerResult.nodes();
            current = nodes.size() == 1
                    ? providerContentWithoutRootIdentity(nodes.get(0))
                    : new Node().items(
                    providerContentWithoutRootIdentity(nodes));
        }

        if (index == segments.size()) {
            return DemandExpansion.established(current);
        }
        if (current == null) {
            return DemandExpansion.absent(null);
        }

        String segment = segments.get(index);
        if (Properties.OBJECT_BLUE_ID.equals(segment)) {
            return DemandExpansion.absent(current);
        }
        if (Properties.OBJECT_ITEMS.equals(segment)) {
            if (index + 1 >= segments.size()
                    || current.getItems() == null) {
                return DemandExpansion.absent(current);
            }
            int itemIndex;
            try {
                itemIndex = Integer.parseInt(
                        segments.get(index + 1));
            } catch (NumberFormatException invalidIndex) {
                return DemandExpansion.absent(current);
            }
            if (itemIndex < 0
                    || itemIndex >= current.getItems().size()) {
                return DemandExpansion.absent(current);
            }
            DemandExpansion child = expandDemand(
                    current.getItems().get(itemIndex),
                    segments,
                    index + 2,
                    context);
            current.getItems().set(itemIndex, child.node);
            return child.withNode(current);
        }

        Node child = semanticChild(current, segment);
        if (child == null) {
            return DemandExpansion.absent(current);
        }
        DemandExpansion expandedChild = expandDemand(
                child, segments, index + 1, context);
        setSemanticChild(current, segment, expandedChild.node);
        return expandedChild.withNode(current);
    }

    private Node semanticChild(Node node, String segment) {
        if (Properties.OBJECT_NAME.equals(segment)) {
            return node.getName() == null
                    ? null : new Node().value(node.getName());
        }
        if (Properties.OBJECT_DESCRIPTION.equals(segment)) {
            return node.getDescription() == null
                    ? null : new Node().value(node.getDescription());
        }
        if (Properties.OBJECT_TYPE.equals(segment)) {
            return node.getType();
        }
        if (Properties.OBJECT_ITEM_TYPE.equals(segment)) {
            return node.getItemType();
        }
        if (Properties.OBJECT_KEY_TYPE.equals(segment)) {
            return node.getKeyType();
        }
        if (Properties.OBJECT_VALUE_TYPE.equals(segment)) {
            return node.getValueType();
        }
        if (Properties.OBJECT_VALUE.equals(segment)) {
            return node.getRawValue() == null
                    ? null : new Node().value(node.getRawValue());
        }
        if (Properties.OBJECT_SCHEMA.equals(segment)) {
            return node.getSchema() == null
                    ? null
                    : JSON_MAPPER.convertValue(
                    SchemaToMapListOrValue.get(
                            node.getSchema(),
                            NodeToMapListOrValue::get),
                    Node.class);
        }
        if (Properties.OBJECT_CONTRACTS.equals(segment)) {
            return node.getContracts();
        }
        return node.getProperties() == null
                ? null : node.getProperties().get(segment);
    }

    private void setSemanticChild(
            Node node, String segment, Node child) {
        if (Properties.OBJECT_TYPE.equals(segment)) {
            node.type(child);
        } else if (Properties.OBJECT_ITEM_TYPE.equals(segment)) {
            node.itemType(child);
        } else if (Properties.OBJECT_KEY_TYPE.equals(segment)) {
            node.keyType(child);
        } else if (Properties.OBJECT_VALUE_TYPE.equals(segment)) {
            node.valueType(child);
        } else if (Properties.OBJECT_CONTRACTS.equals(segment)) {
            node.contracts(child);
        } else if (Properties.OBJECT_SCHEMA.equals(segment)) {
            node.schema(child == null
                    ? null
                    : NodeDeserializer.parseSchema(
                    JSON_MAPPER.valueToTree(
                            NodeToMapListOrValue.get(child)),
                    JsonPointer.append(
                            JsonPointer.ROOT,
                            Properties.OBJECT_SCHEMA)));
        } else if (!Properties.OBJECT_NAME.equals(segment)
                && !Properties.OBJECT_DESCRIPTION.equals(segment)
                && !Properties.OBJECT_VALUE.equals(segment)) {
            Map<String, Node> properties = node.getProperties();
            if (properties != null) {
                properties.put(segment, child);
            }
        }
    }

    private List<Node> expandReferences(List<Node> nodes) {
        List<Node> expanded = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            expanded.add(expandReferences(node));
        }
        return expanded;
    }

    private Schema expandReferences(Schema schema) {
        if (schema == null) {
            return null;
        }
        if (schema.isReferenceOnly()) {
            NodeProviderResult result =
                    nodeProvider.fetchResultByBlueId(
                            schema.getBlueId());
            if (result.outcome() != NodeProviderOutcome.FOUND) {
                throw new IllegalArgumentException(
                        "Unable to expand schema reference "
                                + schema.getBlueId() + ": "
                                + result.outcome());
            }
            List<Node> nodes = result.nodes();
            if (nodes.size() != 1) {
                throw new IllegalArgumentException(
                        "Schema references must materialize one object node: "
                                + schema.getBlueId());
            }
            Schema materialized = NodeDeserializer.parseSchema(
                    JSON_MAPPER.valueToTree(
                            NodeToMapListOrValue.get(
                                    providerContentWithoutRootIdentity(
                                            nodes.get(0)))),
                    JsonPointer.append(
                            JsonPointer.ROOT,
                            Properties.OBJECT_SCHEMA));
            if (materialized.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Schema provider returned a reference-only wrapper for "
                                + schema.getBlueId());
            }
            return expandReferences(materialized);
        }
        Schema expanded = schema.clone();
        expanded.required(expandReferences(expanded.getRequired()));
        expanded.minLength(expandReferences(expanded.getMinLength()));
        expanded.maxLength(expandReferences(expanded.getMaxLength()));
        expanded.minimum(expandReferences(expanded.getMinimum()));
        expanded.maximum(expandReferences(expanded.getMaximum()));
        expanded.exclusiveMinimum(expandReferences(
                expanded.getExclusiveMinimum()));
        expanded.exclusiveMaximum(expandReferences(
                expanded.getExclusiveMaximum()));
        expanded.multipleOf(expandReferences(expanded.getMultipleOf()));
        expanded.minItems(expandReferences(expanded.getMinItems()));
        expanded.maxItems(expandReferences(expanded.getMaxItems()));
        expanded.uniqueItems(expandReferences(
                expanded.getUniqueItems()));
        expanded.minFields(expandReferences(expanded.getMinFields()));
        expanded.maxFields(expandReferences(expanded.getMaxFields()));
        if (expanded.getEnum() != null) {
            expanded.enumValues(expandReferences(expanded.getEnum()));
        }
        return expanded;
    }

    private Node providerContentWithoutRootIdentity(Node node) {
        Node canonical = node.clone();
        if (canonical.getBlueId() != null
                && !canonical.isReferenceOnly()) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private List<Node> providerContentWithoutRootIdentity(
            List<Node> nodes) {
        List<Node> canonical = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            canonical.add(providerContentWithoutRootIdentity(node));
        }
        return canonical;
    }

    private static final class LimitedExpansionContext {
        private final int maximum;
        private final Set<String> expandedBlueIds =
                new LinkedHashSet<>();
        private final Set<String> outstandingBlueIds =
                new LinkedHashSet<>();
        private NodeProviderOutcome providerOutcome;

        private LimitedExpansionContext(int maximum) {
            this.maximum = maximum;
        }

        private boolean tryAcquire(String blueId) {
            if (expandedBlueIds.contains(blueId)) {
                return true;
            }
            if (expandedBlueIds.size() >= maximum) {
                outstandingBlueIds.add(blueId);
                return false;
            }
            expandedBlueIds.add(blueId);
            return true;
        }
    }

    private static final class DemandExpansion {
        private final Node node;
        private final BlueOperationOutcome outcome;
        private final String reason;

        private DemandExpansion(
                Node node,
                BlueOperationOutcome outcome,
                String reason) {
            this.node = node;
            this.outcome = outcome;
            this.reason = reason;
        }

        private static DemandExpansion established(Node node) {
            return new DemandExpansion(
                    node, BlueOperationOutcome.ESTABLISHED, null);
        }

        private static DemandExpansion absent(Node node) {
            return new DemandExpansion(
                    node,
                    BlueOperationOutcome.ABSENT,
                    "Demanded path is semantically absent.");
        }

        private static DemandExpansion incomplete(
                Node node, String reason) {
            return new DemandExpansion(
                    node, BlueOperationOutcome.INCOMPLETE, reason);
        }

        private static DemandExpansion invalid(
                Node node, String reason) {
            return new DemandExpansion(
                    node, BlueOperationOutcome.INVALID, reason);
        }

        private DemandExpansion withNode(Node replacement) {
            return new DemandExpansion(
                    replacement, outcome, reason);
        }
    }
}
