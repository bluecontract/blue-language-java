package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Exact, content-addressed physical fragments for one or more ordinary Blue
 * roots.
 *
 * <p>Every inline semantic {@link Node} is retained as a shallow fragment. Its
 * direct semantic Node children are represented by pure BlueId references,
 * while scalar and non-Node metadata remain inline. In particular, the Node
 * wrappers used to hold scalar schema-keyword values remain inline because
 * those keywords hash their scalar values rather than Node identities.
 * Replacing an inline child with a reference to that child's exact identity
 * preserves the identity of every ancestor.</p>
 *
 * <p>This utility deliberately does not flatten cyclic sets. A finalized
 * cyclic-member reference ({@code MASTER#index}) is retained as an opaque
 * external edge: it is recorded in the direct-edge graph but is neither
 * recursively fragmented nor served by this fragment set's local provider.
 * Materializing that edge requires the proof supplied by a cyclic-set-aware
 * provider. Cyclic-calculation placeholders, object cycles, and cycles assembled
 * by mixing inline content with references to other admitted fragments remain
 * invalid.</p>
 */
public final class ExactNodeGraphFragments {

    private final List<RootRepresentation> roots;
    private final List<String> blueIds;
    private final SortedMap<String, Node> fragments;
    private final NodeProvider provider;

    public ExactNodeGraphFragments(Node... exactRoots) {
        this(requireRootArray(exactRoots));
    }

    public ExactNodeGraphFragments(Collection<? extends Node> exactRoots) {
        Objects.requireNonNull(exactRoots, "exactRoots");
        if (exactRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one exact ordinary Blue root is required.");
        }

        List<Node> suppliedRoots = new ArrayList<>(exactRoots.size());
        int index = 0;
        for (Node root : exactRoots) {
            if (root == null) {
                throw new IllegalArgumentException(
                        "Exact ordinary Blue root " + index + " must not be null.");
            }
            suppliedRoots.add(root);
            index++;
        }

        OrdinaryGraphValidator validator = new OrdinaryGraphValidator();
        for (int rootIndex = 0; rootIndex < suppliedRoots.size(); rootIndex++) {
            Node root = suppliedRoots.get(rootIndex);
            validator.validate(root, "root[" + rootIndex + "]");
            if (root.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Exact ordinary Blue root " + rootIndex
                                + " is a pure reference; exact content is required.");
            }
        }

        FragmentBuilder builder = new FragmentBuilder();
        List<RootRepresentation> retainedRoots =
                new ArrayList<>(suppliedRoots.size());
        for (int rootIndex = 0; rootIndex < suppliedRoots.size(); rootIndex++) {
            Node root = suppliedRoots.get(rootIndex);
            FragmentRecord record =
                    builder.record(root, "root[" + rootIndex + "]");
            retainedRoots.add(new RootRepresentation(
                    record.blueId, root, record.directFragment));
        }
        builder.rejectMixedReferenceCycles();

        this.roots = Collections.unmodifiableList(retainedRoots);
        this.fragments = immutableFragmentSnapshot(builder.fragments);
        this.blueIds = Collections.unmodifiableList(
                new ArrayList<>(this.fragments.keySet()));
        this.provider = new FragmentProvider(this.fragments);
    }

    /**
     * Root representations in caller-supplied root order.
     */
    public List<RootRepresentation> roots() {
        return roots;
    }

    /**
     * All locally recorded fragment identities in canonical lexical order.
     */
    public List<String> blueIds() {
        return blueIds;
    }

    /**
     * A lexically ordered, unmodifiable snapshot keyed by exact BlueId.
     *
     * <p>The returned nodes are defensive copies. Mutating one cannot change
     * this fragment set or its provider.</p>
     */
    public Map<String, Node> fragments() {
        return immutableFragmentSnapshot(fragments);
    }

    /**
     * An in-memory provider over these exact shallow fragments.
     *
     * <p>Known identities return {@link NodeProviderOutcome#FOUND}; unknown
     * identities retain normal provider miss semantics and return
     * {@link NodeProviderOutcome#NOT_FOUND}. This includes opaque finalized
     * cyclic-member edges, whose content must come from a separate
     * cyclic-set-aware provider.</p>
     */
    public NodeProvider provider() {
        return provider;
    }

    private static Collection<? extends Node> requireRootArray(Node[] exactRoots) {
        Objects.requireNonNull(exactRoots, "exactRoots");
        return Arrays.asList(exactRoots);
    }

    private static SortedMap<String, Node> immutableFragmentSnapshot(
            Map<String, Node> source) {
        SortedMap<String, Node> snapshot = new TreeMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableSortedMap(snapshot);
    }

    /**
     * The three physical forms of one admitted root.
     */
    public static final class RootRepresentation {

        private final String blueId;
        private final Node original;
        private final Node directFragment;

        private RootRepresentation(String blueId,
                                   Node original,
                                   Node directFragment) {
            this.blueId = Objects.requireNonNull(blueId, "blueId");
            this.original = Objects.requireNonNull(original, "original").clone();
            this.directFragment = Objects.requireNonNull(
                    directFragment, "directFragment").clone();
        }

        public String blueId() {
            return blueId;
        }

        public Node original() {
            return original.clone();
        }

        public Node directFragment() {
            return directFragment.clone();
        }

        public Node pureReference() {
            return new Node().blueId(blueId);
        }
    }

    private static final class FragmentBuilder {

        private final IdentityHashMap<Node, FragmentRecord> records =
                new IdentityHashMap<>();
        private final IdentityHashMap<Node, String> active =
                new IdentityHashMap<>();
        private final SortedMap<String, Node> fragments = new TreeMap<>();
        private final SortedMap<String, SortedSet<String>> edges =
                new TreeMap<>();

        private FragmentRecord record(Node node, String path) {
            if (node.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Internal error: a pure reference cannot be recorded as "
                                + "exact content at " + path + ".");
            }
            FragmentRecord retained = records.get(node);
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
                String originalBlueId = calculateExactBlueId(node, path);
                SortedSet<String> directEdges = new TreeSet<>();
                Node direct = node.clone();

                direct.type(referenceFor(
                        node.getType(), path + "/type", directEdges));
                direct.itemType(referenceFor(
                        node.getItemType(), path + "/itemType", directEdges));
                direct.keyType(referenceFor(
                        node.getKeyType(), path + "/keyType", directEdges));
                direct.valueType(referenceFor(
                        node.getValueType(), path + "/valueType", directEdges));
                direct.contracts(referenceFor(
                        node.getContracts(), path + "/contracts", directEdges));
                direct.blue(referenceFor(
                        node.getBlue(), path + "/blue", directEdges));

                if (node.getItems() != null) {
                    List<Node> directItems =
                            new ArrayList<>(node.getItems().size());
                    for (int itemIndex = 0;
                         itemIndex < node.getItems().size();
                         itemIndex++) {
                        directItems.add(referenceFor(
                                node.getItems().get(itemIndex),
                                path + "/items/" + itemIndex,
                                directEdges));
                    }
                    direct.items(directItems);
                }

                if (node.getProperties() != null) {
                    Map<String, Node> directProperties = new LinkedHashMap<>();
                    SortedMap<String, Node> orderedProperties =
                            new TreeMap<>(node.getProperties());
                    for (Map.Entry<String, Node> property
                            : orderedProperties.entrySet()) {
                        directProperties.put(property.getKey(), referenceFor(
                                property.getValue(),
                                path + "/" + property.getKey(),
                                directEdges));
                    }
                    direct.properties(directProperties);
                }

                if (node.getSchema() != null) {
                    direct.schema(fragmentSchema(
                            node.getSchema(), path + "/schema", directEdges));
                }
                if (node.getPreviousBlueId() != null) {
                    directEdges.add(node.getPreviousBlueId());
                }

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

                Node existing = fragments.get(originalBlueId);
                if (existing == null) {
                    fragments.put(originalBlueId, direct.clone());
                }
                edges.computeIfAbsent(
                        originalBlueId, ignored -> new TreeSet<>())
                        .addAll(directEdges);

                FragmentRecord created =
                        new FragmentRecord(originalBlueId, direct);
                records.put(node, created);
                return created;
            } finally {
                active.remove(node);
            }
        }

        private Node referenceFor(Node child,
                                  String path,
                                  Set<String> directEdges) {
            if (child == null) {
                return null;
            }
            String childBlueId;
            if (child.isReferenceOnly()) {
                childBlueId = requireFinalReference(
                        child.getBlueId(), path + "/blueId");
            } else {
                childBlueId = record(child, path).blueId;
            }
            directEdges.add(childBlueId);
            return new Node().blueId(childBlueId);
        }

        private Schema fragmentSchema(Schema schema,
                                      String path,
                                      Set<String> directEdges) {
            if (schema.isReferenceOnly()) {
                String schemaBlueId = requireFinalReference(
                        schema.getBlueId(), path + "/blueId");
                directEdges.add(schemaBlueId);
                return new Schema().blueId(schemaBlueId);
            }

            Schema direct = schema.clone();
            direct.minimum(fragmentSchemaValue(
                    schema.getMinimum(), path + "/minimum", directEdges));
            direct.maximum(fragmentSchemaValue(
                    schema.getMaximum(), path + "/maximum", directEdges));
            direct.exclusiveMinimum(fragmentSchemaValue(
                    schema.getExclusiveMinimum(),
                    path + "/exclusiveMinimum", directEdges));
            direct.exclusiveMaximum(fragmentSchemaValue(
                    schema.getExclusiveMaximum(),
                    path + "/exclusiveMaximum", directEdges));
            direct.multipleOf(fragmentSchemaValue(
                    schema.getMultipleOf(), path + "/multipleOf", directEdges));
            if (schema.getEnum() != null) {
                List<Node> directEnum =
                        new ArrayList<>(schema.getEnum().size());
                for (int enumIndex = 0;
                     enumIndex < schema.getEnum().size();
                     enumIndex++) {
                    directEnum.add(fragmentSchemaValue(
                            schema.getEnum().get(enumIndex),
                            path + "/enum/" + enumIndex,
                            directEdges));
                }
                direct.enumValues(directEnum);
            }
            return direct;
        }

        /*
         * Schema count/boolean keywords and plain numeric/enum values are
         * encoded as raw schema values, not as semantic Node children. Only an
         * explicit, decorated numeric/enum Node is hash-linked and therefore
         * replaceable by a BlueId reference.
         */
        private Node fragmentSchemaValue(Node value,
                                         String path,
                                         Set<String> directEdges) {
            if (value == null) {
                return null;
            }
            return isPlainSchemaScalar(value)
                    ? value.clone()
                    : referenceFor(value, path, directEdges);
        }

        private void rejectMixedReferenceCycles() {
            Map<String, VisitState> states = new TreeMap<>();
            for (String blueId : fragments.keySet()) {
                rejectMixedReferenceCycles(blueId, states, new ArrayList<String>());
            }
        }

        private void rejectMixedReferenceCycles(
                String blueId,
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
                                + path + ". Cyclic sets require cyclic-aware proof.");
            }
            states.put(blueId, VisitState.ACTIVE);
            path.add(blueId);
            SortedSet<String> targets = edges.get(blueId);
            if (targets != null) {
                for (String target : targets) {
                    if (fragments.containsKey(target)) {
                        rejectMixedReferenceCycles(
                                target, states, new ArrayList<>(path));
                    }
                }
            }
            states.put(blueId, VisitState.COMPLETE);
        }
    }

    private static final class OrdinaryGraphValidator {

        private final IdentityHashMap<Node, String> activeNodes =
                new IdentityHashMap<>();
        private final IdentityHashMap<Node, Boolean> completeNodes =
                new IdentityHashMap<>();
        private final IdentityHashMap<Object, String> activeValues =
                new IdentityHashMap<>();
        private final IdentityHashMap<Object, Boolean> completeValues =
                new IdentityHashMap<>();

        private void validate(Node node, String path) {
            if (node == null) {
                return;
            }
            if (completeNodes.containsKey(node)) {
                return;
            }
            String activePath = activeNodes.put(node, path);
            if (activePath != null) {
                throw new IllegalArgumentException(
                        "Mixed reference/object cycle or Blue object cycle "
                                + "between " + activePath + " and " + path
                                + " cannot be fragmented.");
            }
            try {
                if (node.getBlueId() != null) {
                    requireFinalReference(
                            node.getBlueId(), path + "/blueId");
                    if (!node.isReferenceOnly()) {
                        throw new IllegalArgumentException(
                                "Mixed reference/object content at " + path
                                        + ": a BlueId reference must be pure, "
                                        + "and a node's own BlueId must not "
                                        + "appear in its content.");
                    }
                    return;
                }

                validate(node.getType(), path + "/type");
                validate(node.getItemType(), path + "/itemType");
                validate(node.getKeyType(), path + "/keyType");
                validate(node.getValueType(), path + "/valueType");
                validate(node.getContracts(), path + "/contracts");
                validate(node.getBlue(), path + "/blue");
                if (node.getItems() != null) {
                    for (int itemIndex = 0;
                         itemIndex < node.getItems().size();
                         itemIndex++) {
                        validate(node.getItems().get(itemIndex),
                                path + "/items/" + itemIndex);
                    }
                }
                if (node.getProperties() != null) {
                    for (Map.Entry<String, Node> property
                            : node.getProperties().entrySet()) {
                        validate(property.getValue(),
                                path + "/" + property.getKey());
                    }
                }
                validate(node.getSchema(), path + "/schema");
                validateValue(node.getRawValue(), path + "/value");
                if (node.getPreviousBlueId() != null) {
                    BlueIds.requirePlainBlueId(
                            node.getPreviousBlueId(),
                            path + "/$previous/blueId");
                }
            } finally {
                activeNodes.remove(node);
                completeNodes.put(node, Boolean.TRUE);
            }
        }

        private void validate(Schema schema, String path) {
            if (schema == null) {
                return;
            }
            if (schema.getBlueId() != null) {
                requireFinalReference(
                        schema.getBlueId(), path + "/blueId");
                if (!schema.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Mixed reference/object schema at " + path
                                    + ": a schema BlueId reference must be pure.");
                }
                return;
            }
            validate(schema.getRequired(), path + "/required");
            validate(schema.getMinLength(), path + "/minLength");
            validate(schema.getMaxLength(), path + "/maxLength");
            validate(schema.getMinimum(), path + "/minimum");
            validate(schema.getMaximum(), path + "/maximum");
            validate(schema.getExclusiveMinimum(),
                    path + "/exclusiveMinimum");
            validate(schema.getExclusiveMaximum(),
                    path + "/exclusiveMaximum");
            validate(schema.getMultipleOf(), path + "/multipleOf");
            validate(schema.getMinItems(), path + "/minItems");
            validate(schema.getMaxItems(), path + "/maxItems");
            validate(schema.getUniqueItems(), path + "/uniqueItems");
            validate(schema.getMinFields(), path + "/minFields");
            validate(schema.getMaxFields(), path + "/maxFields");
            if (schema.getEnum() != null) {
                for (int enumIndex = 0;
                     enumIndex < schema.getEnum().size();
                     enumIndex++) {
                    validate(schema.getEnum().get(enumIndex),
                            path + "/enum/" + enumIndex);
                }
            }
        }

        private void validateValue(Object value, String path) {
            if (value == null || value instanceof String
                    || value instanceof Number || value instanceof Boolean
                    || value instanceof Character || value instanceof Enum) {
                return;
            }
            if (value instanceof Node || value instanceof Schema) {
                throw new IllegalArgumentException(
                        "Node and Schema objects are not scalar value content "
                                + "at " + path + ".");
            }
            boolean traversable = value instanceof Map
                    || value instanceof Iterable
                    || value.getClass().isArray();
            if (!traversable || completeValues.containsKey(value)) {
                return;
            }
            String activePath = activeValues.put(value, path);
            if (activePath != null) {
                throw new IllegalArgumentException(
                        "Cyclic value content between " + activePath + " and "
                                + path + " cannot be fragmented.");
            }
            try {
                if (value instanceof Map) {
                    for (Map.Entry<?, ?> entry
                            : ((Map<?, ?>) value).entrySet()) {
                        validateValue(entry.getValue(),
                                path + "/" + String.valueOf(entry.getKey()));
                    }
                } else if (value instanceof Iterable) {
                    int index = 0;
                    for (Object item : (Iterable<?>) value) {
                        validateValue(item, path + "/" + index);
                        index++;
                    }
                } else {
                    int length = Array.getLength(value);
                    for (int index = 0; index < length; index++) {
                        validateValue(
                                Array.get(value, index), path + "/" + index);
                    }
                }
            } finally {
                activeValues.remove(value);
                completeValues.put(value, Boolean.TRUE);
            }
        }
    }

    private static String requireFinalReference(String blueId, String path) {
        return BlueIds.requireBlueIdOrCyclicMember(
                BlueIds.requireNoThisPlaceholderOutsideCyclicApi(
                        blueId, path),
                path);
    }

    private static boolean isPlainSchemaScalar(Node node) {
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

    private static String calculateExactBlueId(Node node, String path) {
        try {
            return BlueIdCalculator.calculateBlueId(node);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid exact ordinary Blue content at " + path + ".",
                    invalid);
        }
    }

    private static final class FragmentRecord {

        private final String blueId;
        private final Node directFragment;

        private FragmentRecord(String blueId, Node directFragment) {
            this.blueId = blueId;
            this.directFragment = directFragment.clone();
        }
    }

    private enum VisitState {
        ACTIVE,
        COMPLETE
    }

    private static final class FragmentProvider implements NodeProvider {

        private final SortedMap<String, Node> fragments;

        private FragmentProvider(Map<String, Node> fragments) {
            this.fragments = immutableFragmentSnapshot(fragments);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult result = fetchResultByBlueId(blueId);
            if (result.outcome() == NodeProviderOutcome.FOUND) {
                return result.nodes();
            }
            if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
                throw new IllegalArgumentException(result.diagnostic().orElse(
                        "Stored exact fragment is invalid for " + blueId + "."));
            }
            if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
                throw new IllegalStateException(result.diagnostic().orElse(
                        "Exact fragment provider is unavailable for "
                                + blueId + "."));
            }
            return null;
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            Node fragment = fragments.get(blueId);
            if (fragment == null) {
                return NodeProviderResult.notFound();
            }
            String actualBlueId;
            try {
                actualBlueId = BlueIdCalculator.calculateBlueId(fragment);
            } catch (RuntimeException invalidEvidence) {
                return NodeProviderResult.invalidEvidence(
                        "Stored exact fragment is invalid for requested BlueId "
                                + blueId + ": " + invalidEvidence.getMessage());
            }
            if (!blueId.equals(actualBlueId)) {
                return NodeProviderResult.invalidEvidence(
                        "Stored exact fragment calculated BlueId "
                                + actualBlueId + " instead of requested BlueId "
                                + blueId + ".");
            }
            return NodeProviderResult.found(
                    Collections.singletonList(fragment));
        }
    }
}
