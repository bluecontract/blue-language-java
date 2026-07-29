package blue.language.provider;

import blue.language.utils.Properties;

import blue.language.NodeProvider;
import blue.language.BlueViewPath;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.JsonPointer;

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

import static blue.language.utils.SchemaPropertyConstants.*;

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

    /**
     * Splits every semantic child boundary of supplied exact roots.
     *
     * @param exactRoots non-empty ordinary exact roots
     * @throws IllegalArgumentException when a root is null, a pure reference,
     *                                  cyclic, or otherwise not fragmentable
     */
    public ExactNodeGraphFragments(Node... exactRoots) {
        this(requireRootArray(exactRoots));
    }

    /**
     * Splits every semantic child boundary of supplied exact roots.
     *
     * @param exactRoots non-empty ordinary exact roots
     * @throws IllegalArgumentException when a root is null, a pure reference,
     *                                  cyclic, or otherwise not fragmentable
     */
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
     * Splits one exact ordinary Blue root only at the selected RFC 6901 cuts.
     *
     * <p>Every node on a root-to-cut path becomes one exact fragment. Other
     * descendants stay inline. For example, cuts {@code /a/body} and
     * {@code /archive} produce fragments for the Root, {@code /a},
     * {@code /a/body}, and {@code /archive}. Authored cut order and duplicate
     * cuts do not affect fragment identities or provider results.</p>
     *
     * @param exactRoot exact ordinary Blue content, not a pure reference
     * @param cuts RFC 6901 pointers relative to {@code exactRoot}; the empty
     *             pointer selects the Root
     * @return an immutable exact-fragment graph
     */
    public static ExactNodeGraphFragments split(
            Node exactRoot,
            Collection<String> cuts) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        Objects.requireNonNull(cuts, "cuts");

        OrdinaryGraphValidator validator = new OrdinaryGraphValidator();
        validator.validate(exactRoot, "root[0]");
        if (exactRoot.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Exact ordinary Blue root is a pure reference; "
                            + "exact content is required.");
        }

        SelectiveFragmentBuilder builder =
                new SelectiveFragmentBuilder(cutSelection(cuts));
        FragmentRecord root = builder.record(exactRoot, "root[0]");
        builder.rejectMixedReferenceCycles();
        return new ExactNodeGraphFragments(
                Collections.singletonList(new RootRepresentation(
                        root.blueId, exactRoot, root.directFragment)),
                builder.fragments);
    }

    private ExactNodeGraphFragments(
            List<RootRepresentation> roots,
            Map<String, Node> fragments) {
        this.roots = Collections.unmodifiableList(
                new ArrayList<>(roots));
        this.fragments = immutableFragmentSnapshot(fragments);
        this.blueIds = Collections.unmodifiableList(
                new ArrayList<>(this.fragments.keySet()));
        this.provider = new FragmentProvider(this.fragments);
    }

    /**
     * Root representations in caller-supplied root order.
     *
     * @return immutable retained root representations
     */
    public List<RootRepresentation> roots() {
        return roots;
    }

    /**
     * All locally recorded fragment identities in canonical lexical order.
     *
     * @return immutable lexical identity list
     */
    public List<String> blueIds() {
        return blueIds;
    }

    /**
     * A lexically ordered, unmodifiable snapshot keyed by exact BlueId.
     *
     * <p>The returned nodes are defensive copies. Mutating one cannot change
     * this fragment set or its provider.</p>
     *
     * @return immutable lexical map of defensive fragment copies
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
     *
     * @return immutable in-memory fragment provider
     */
    public NodeProvider provider() {
        return provider;
    }

    private static Collection<? extends Node> requireRootArray(Node[] exactRoots) {
        Objects.requireNonNull(exactRoots, "exactRoots");
        return Arrays.asList(exactRoots);
    }

    private static CutSelection cutSelection(Collection<String> cuts) {
        CutSelection root = new CutSelection();
        for (String cut : cuts) {
            if (cut == null) {
                throw new IllegalArgumentException(
                        "Exact graph fragment cut must not be null.");
            }
            CutSelection cursor = root;
            for (String segment : BlueViewPath.split(cut)) {
                cursor = cursor.children.computeIfAbsent(
                        segment, ignored -> new CutSelection());
            }
            cursor.selected = true;
        }
        return root;
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
            this.blueId = Objects.requireNonNull(blueId, Properties.OBJECT_BLUE_ID);
            this.original = Objects.requireNonNull(original, "original").clone();
            this.directFragment = Objects.requireNonNull(
                    directFragment, "directFragment").clone();
        }

        /**
         * Returns the exact root identity.
         *
         * @return exact root BlueId
         */
        public String blueId() {
            return blueId;
        }

        /**
         * Returns a defensive copy of the exact caller-supplied root.
         *
         * @return exact root copy
         */
        public Node original() {
            return original.clone();
        }

        /**
         * Returns a defensive copy whose fragmented children are pure
         * references.
         *
         * @return direct-fragment copy
         */
        public Node directFragment() {
            return directFragment.clone();
        }

        /**
         * Creates a fresh pure reference to the root identity.
         *
         * @return new pure-reference node
         */
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
                        node.getType(),
                        pointerPath(path, Properties.OBJECT_TYPE),
                        directEdges));
                direct.itemType(referenceFor(
                        node.getItemType(),
                        pointerPath(path, Properties.OBJECT_ITEM_TYPE),
                        directEdges));
                direct.keyType(referenceFor(
                        node.getKeyType(),
                        pointerPath(path, Properties.OBJECT_KEY_TYPE),
                        directEdges));
                direct.valueType(referenceFor(
                        node.getValueType(),
                        pointerPath(path, Properties.OBJECT_VALUE_TYPE),
                        directEdges));
                direct.contracts(referenceFor(
                        node.getContracts(),
                        pointerPath(path, Properties.OBJECT_CONTRACTS),
                        directEdges));
                direct.blue(referenceFor(
                        node.getBlue(),
                        pointerPath(path, Properties.OBJECT_BLUE),
                        directEdges));

                if (node.getItems() != null) {
                    List<Node> directItems =
                            new ArrayList<>(node.getItems().size());
                    for (int itemIndex = 0;
                         itemIndex < node.getItems().size();
                         itemIndex++) {
                        directItems.add(referenceFor(
                                node.getItems().get(itemIndex),
                                pointerPath(
                                        pointerPath(
                                                path,
                                                Properties.OBJECT_ITEMS),
                                        String.valueOf(itemIndex)),
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
                                pointerPath(path, property.getKey()),
                                directEdges));
                    }
                    direct.properties(directProperties);
                }

                if (node.getSchema() != null) {
                    direct.schema(fragmentSchema(
                            node.getSchema(),
                            pointerPath(path, Properties.OBJECT_SCHEMA),
                            directEdges));
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
                        child.getBlueId(),
                        pointerPath(path, Properties.OBJECT_BLUE_ID));
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
                        schema.getBlueId(),
                        pointerPath(path, Properties.OBJECT_BLUE_ID));
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
            if (schema.getEnum() != null) {
                List<Node> directEnum =
                        new ArrayList<>(schema.getEnum().size());
                for (int enumIndex = 0;
                     enumIndex < schema.getEnum().size();
                     enumIndex++) {
                    directEnum.add(fragmentSchemaValue(
                            schema.getEnum().get(enumIndex),
                            pointerPath(
                                    pointerPath(path, KEY_ENUM),
                                    String.valueOf(enumIndex)),
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

    private static final class SelectiveFragmentBuilder {

        private final CutSelection rootSelection;
        private final SortedMap<String, Node> fragments = new TreeMap<>();
        private final SortedMap<String, SortedSet<String>> edges =
                new TreeMap<>();

        private SelectiveFragmentBuilder(CutSelection rootSelection) {
            this.rootSelection = rootSelection;
        }

        private FragmentRecord record(Node node, String path) {
            return record(node, rootSelection, path);
        }

        private FragmentRecord record(
                Node node,
                CutSelection selection,
                String path) {
            if (node == null || node.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "A selected exact fragment cut requires inline Node "
                                + "content at " + path + ".");
            }

            String originalBlueId = calculateExactBlueId(node, path);
            Node direct = node.clone();
            for (Map.Entry<String, CutSelection> child
                    : selection.children.entrySet()) {
                applyCut(node, direct, child.getKey(),
                        child.getValue(), path);
            }

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
            return new FragmentRecord(originalBlueId, direct);
        }

        private void applyCut(
                Node source,
                Node direct,
                String segment,
                CutSelection selection,
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
                        segment, source.getItems().size(), path);
                Node child = source.getItems().get(index);
                direct.getItems().set(index,
                        fragmentReference(child, selection, path));
                return;
            }
            Map<String, Node> properties = source.getProperties();
            if (properties == null || !properties.containsKey(segment)) {
                throw new IllegalArgumentException(
                        "Exact graph fragment cut does not select a Node at "
                                + path + ".");
            }
            direct.getProperties().put(segment, fragmentReference(
                    properties.get(segment), selection, path));
        }

        private void applyItemCuts(
                Node source,
                Node direct,
                CutSelection selection,
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
            for (Map.Entry<String, CutSelection> item
                    : selection.children.entrySet()) {
                int index = requireItemIndex(
                        item.getKey(), source.getItems().size(),
                        pointerPath(path, item.getKey()));
                direct.getItems().set(index, fragmentReference(
                        source.getItems().get(index),
                        item.getValue(),
                        pointerPath(path, item.getKey())));
            }
        }

        private void applySchemaCuts(
                Schema source,
                Schema direct,
                CutSelection selection,
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
            for (Map.Entry<String, CutSelection> keyword
                    : selection.children.entrySet()) {
                String keywordPath =
                        pointerPath(path, keyword.getKey());
                switch (keyword.getKey()) {
                    case KEY_REQUIRED:
                        direct.required(fragmentSchemaReference(
                                source.getRequired(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MIN_LENGTH:
                        direct.minLength(fragmentSchemaReference(
                                source.getMinLength(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MAX_LENGTH:
                        direct.maxLength(fragmentSchemaReference(
                                source.getMaxLength(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MINIMUM:
                        direct.minimum(fragmentSchemaReference(
                                source.getMinimum(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MAXIMUM:
                        direct.maximum(fragmentSchemaReference(
                                source.getMaximum(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_EXCLUSIVE_MINIMUM:
                        direct.exclusiveMinimum(fragmentSchemaReference(
                                source.getExclusiveMinimum(),
                                keyword.getValue(), keywordPath));
                        break;
                    case KEY_EXCLUSIVE_MAXIMUM:
                        direct.exclusiveMaximum(fragmentSchemaReference(
                                source.getExclusiveMaximum(),
                                keyword.getValue(), keywordPath));
                        break;
                    case KEY_MULTIPLE_OF:
                        direct.multipleOf(fragmentSchemaReference(
                                source.getMultipleOf(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MIN_ITEMS:
                        direct.minItems(fragmentSchemaReference(
                                source.getMinItems(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MAX_ITEMS:
                        direct.maxItems(fragmentSchemaReference(
                                source.getMaxItems(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_UNIQUE_ITEMS:
                        direct.uniqueItems(fragmentSchemaReference(
                                source.getUniqueItems(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MIN_FIELDS:
                        direct.minFields(fragmentSchemaReference(
                                source.getMinFields(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_MAX_FIELDS:
                        direct.maxFields(fragmentSchemaReference(
                                source.getMaxFields(), keyword.getValue(),
                                keywordPath));
                        break;
                    case KEY_ENUM:
                        applySchemaEnumCuts(
                                source, direct, keyword.getValue(),
                                keywordPath);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown schema cut segment at "
                                        + keywordPath + ".");
                }
            }
        }

        private void applySchemaEnumCuts(
                Schema source,
                Schema direct,
                CutSelection selection,
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
            for (Map.Entry<String, CutSelection> value
                    : selection.children.entrySet()) {
                int index = requireItemIndex(
                        value.getKey(), source.getEnum().size(),
                        pointerPath(path, value.getKey()));
                values.set(index, fragmentSchemaReference(
                        source.getEnum().get(index),
                        value.getValue(),
                        pointerPath(path, value.getKey())));
            }
            direct.enumValues(values);
        }

        private Node fragmentSchemaReference(
                Node child,
                CutSelection selection,
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
                CutSelection selection,
                String path) {
            if (child == null || child.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "A selected exact fragment cut requires inline Node "
                                + "content at " + path + ".");
            }
            return new Node().blueId(
                    record(child, selection, path).blueId);
        }

        private void rejectMixedReferenceCycles() {
            Map<String, VisitState> states = new TreeMap<>();
            for (String blueId : fragments.keySet()) {
                rejectMixedReferenceCycles(
                        blueId, states, new ArrayList<String>());
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
                                target, states, new ArrayList<>(path));
                    }
                }
            }
            states.put(blueId, VisitState.COMPLETE);
        }
    }

    private static void collectReferenceIds(
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

    private static int requireItemIndex(
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
                            + "supported range at " + path + ".", tooLarge);
        }
        if (index >= size) {
            throw new IllegalArgumentException(
                    "Exact graph fragment list index is absent at "
                            + path + ".");
        }
        return index;
    }

    private static String pointerPath(String parent, String segment) {
        return JsonPointer.append(parent, segment);
    }

    private static final class CutSelection {

        private final SortedMap<String, CutSelection> children =
                new TreeMap<>();
        private boolean selected;
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
                            node.getBlueId(),
                            pointerPath(path, Properties.OBJECT_BLUE_ID));
                    if (!node.isReferenceOnly()) {
                        throw new IllegalArgumentException(
                                "Mixed reference/object content at " + path
                                        + ": a BlueId reference must be pure, "
                                        + "and a node's own BlueId must not "
                                        + "appear in its content.");
                    }
                    return;
                }

                validate(node.getType(),
                        pointerPath(path, Properties.OBJECT_TYPE));
                validate(node.getItemType(),
                        pointerPath(path, Properties.OBJECT_ITEM_TYPE));
                validate(node.getKeyType(),
                        pointerPath(path, Properties.OBJECT_KEY_TYPE));
                validate(node.getValueType(),
                        pointerPath(path, Properties.OBJECT_VALUE_TYPE));
                validate(node.getContracts(),
                        pointerPath(path, Properties.OBJECT_CONTRACTS));
                validate(node.getBlue(),
                        pointerPath(path, Properties.OBJECT_BLUE));
                if (node.getItems() != null) {
                    for (int itemIndex = 0;
                         itemIndex < node.getItems().size();
                         itemIndex++) {
                        validate(node.getItems().get(itemIndex),
                                pointerPath(
                                        pointerPath(
                                                path,
                                                Properties.OBJECT_ITEMS),
                                        String.valueOf(itemIndex)));
                    }
                }
                if (node.getProperties() != null) {
                    for (Map.Entry<String, Node> property
                            : node.getProperties().entrySet()) {
                        validate(property.getValue(),
                                pointerPath(path, property.getKey()));
                    }
                }
                validate(node.getSchema(),
                        pointerPath(path, Properties.OBJECT_SCHEMA));
                validateValue(node.getRawValue(),
                        pointerPath(path, Properties.OBJECT_VALUE));
                if (node.getPreviousBlueId() != null) {
                    BlueIds.requirePlainBlueId(
                            node.getPreviousBlueId(),
                            pointerPath(
                                    pointerPath(
                                            path,
                                            Properties.LIST_CONTROL_PREVIOUS),
                                    Properties.OBJECT_BLUE_ID));
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
                        schema.getBlueId(),
                        pointerPath(path, Properties.OBJECT_BLUE_ID));
                if (!schema.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Mixed reference/object schema at " + path
                                    + ": a schema BlueId reference must be pure.");
                }
                return;
            }
            validate(schema.getRequired(), pointerPath(path, KEY_REQUIRED));
            validate(schema.getMinLength(), pointerPath(path, KEY_MIN_LENGTH));
            validate(schema.getMaxLength(), pointerPath(path, KEY_MAX_LENGTH));
            validate(schema.getMinimum(), pointerPath(path, KEY_MINIMUM));
            validate(schema.getMaximum(), pointerPath(path, KEY_MAXIMUM));
            validate(schema.getExclusiveMinimum(),
                    pointerPath(path, KEY_EXCLUSIVE_MINIMUM));
            validate(schema.getExclusiveMaximum(),
                    pointerPath(path, KEY_EXCLUSIVE_MAXIMUM));
            validate(schema.getMultipleOf(),
                    pointerPath(path, KEY_MULTIPLE_OF));
            validate(schema.getMinItems(), pointerPath(path, KEY_MIN_ITEMS));
            validate(schema.getMaxItems(), pointerPath(path, KEY_MAX_ITEMS));
            validate(schema.getUniqueItems(),
                    pointerPath(path, KEY_UNIQUE_ITEMS));
            validate(schema.getMinFields(), pointerPath(path, KEY_MIN_FIELDS));
            validate(schema.getMaxFields(), pointerPath(path, KEY_MAX_FIELDS));
            if (schema.getEnum() != null) {
                for (int enumIndex = 0;
                     enumIndex < schema.getEnum().size();
                     enumIndex++) {
                    validate(schema.getEnum().get(enumIndex),
                            pointerPath(
                                    pointerPath(path, KEY_ENUM),
                                    String.valueOf(enumIndex)));
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
                                pointerPath(
                                        path,
                                        String.valueOf(entry.getKey())));
                    }
                } else if (value instanceof Iterable) {
                    int index = 0;
                    for (Object item : (Iterable<?>) value) {
                        validateValue(
                                item,
                                pointerPath(path, String.valueOf(index)));
                        index++;
                    }
                } else {
                    int length = Array.getLength(value);
                    for (int index = 0; index < length; index++) {
                        validateValue(
                                Array.get(value, index),
                                pointerPath(path, String.valueOf(index)));
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
