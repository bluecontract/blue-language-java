package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.List;
import java.util.Map;

/**
 * Immutable exact Blue value used by snapshots and processing hot paths.
 *
 * <p>This class owns immutable state, defensive boundary views, and node-local
 * memoization. Construction, navigation, conversion, identity, editing,
 * structural keys, and cache weighting are delegated to focused stateless
 * collaborators.</p>
 */
public final class FrozenNode {

    final String name;
    final String description;
    final FrozenNode type;
    final FrozenNode itemType;
    final FrozenNode keyType;
    final FrozenNode valueType;
    final Object value;
    final List<FrozenNode> items;
    final Map<String, FrozenNode> properties;
    final FrozenNode contracts;
    final String referenceBlueId;
    final Schema schema;
    final String mergePolicy;
    final String previousBlueId;
    final Integer position;
    final FrozenNode blue;
    final boolean inlineValue;
    final boolean strictCanonical;
    final boolean strictBlueIdValidation;
    final boolean previousAnchorContext;
    final boolean containsCyclicSetReference;
    final boolean containsSchema;
    final boolean containsNestedTypedObjectPayload;
    final boolean constructionModeNormalized;

    // These caches deliberately remain node-local. Retained-weight estimation
    // observes them without triggering their calculation.
    private volatile String blueId;
    private volatile ResolvedStructuralKey resolvedStructuralKey;

    FrozenNode(FrozenNodeBuilder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.type = builder.type;
        this.itemType = builder.itemType;
        this.keyType = builder.keyType;
        this.valueType = builder.valueType;
        this.value = builder.nodeValue;
        this.items = FrozenNodeBuilder.freezeList(
                builder.items,
                builder.strictCanonical);
        this.properties = FrozenNodeBuilder.freezeMap(builder.properties);
        this.contracts = builder.contracts;
        this.referenceBlueId = builder.referenceBlueId;
        this.schema = builder.schema;
        this.mergePolicy = builder.mergePolicy;
        this.previousBlueId = builder.previousBlueId;
        this.position = builder.position;
        this.blue = builder.blue;
        this.inlineValue = builder.inlineValue;
        this.strictCanonical = builder.strictCanonical;
        this.strictBlueIdValidation = builder.strictBlueIdValidation;
        this.previousAnchorContext = builder.previousAnchorContext;
        this.containsCyclicSetReference =
                FrozenNodeIdentity.containsCyclicSetReference(this);
        this.containsSchema = FrozenNodeIdentity.containsSchema(this);
        this.containsNestedTypedObjectPayload =
                FrozenNodeIdentity.containsNestedTypedObjectPayload(this);
        this.constructionModeNormalized =
                FrozenNodeBuilder.constructionModeNormalized(this);
        FrozenNodeBuilder.validatePayloadShape(this);
        this.blueId = strictCanonical && builder.eagerBlueId
                ? FrozenNodeIdentity.INSTANCE.blueId(this)
                : null;
    }

    /**
     * Creates a strict canonical node with no modeled fields.
     *
     * @return the shared semantics of an empty strict canonical value
     */
    public static FrozenNode empty() {
        return FrozenNodeBuilder.builder().build();
    }

    /**
     * Strictly validates and defensively freezes canonical content.
     *
     * @param node mutable canonical content to freeze
     * @return an immutable strict canonical representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the content is not valid strict
     *         canonical Blue input
     */
    public static FrozenNode fromNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromNode(node);
    }

    /**
     * Defensively freezes exact preprocessed Source input.
     *
     * <p>Unlike {@link #fromNode(Node)}, this entry point accepts ordinary
     * authoring controls and does not claim that the input is a strict
     * Canonical Identity Input.</p>
     *
     * @param node mutable preprocessed Source input to freeze
     * @return an immutable Source representation with no direct BlueId
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the source contains an unsupported
     *         value graph or incompatible payload shapes
     */
    public static FrozenNode fromSourceNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromSourceNode(node);
    }

    /**
     * Defensively freezes a completed resolved view.
     *
     * @param node mutable resolved content to freeze
     * @return an immutable resolved representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the node contains an unsupported
     *         value graph or incompatible payload shapes
     */
    public static FrozenNode fromResolvedNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromResolvedNode(node);
    }

    /**
     * Freezes a resolved view and offers each bottom-up exact representation
     * to an optional structural interner.
     *
     * @param node mutable resolved content to freeze
     * @param interner optional callback that may retain an equal representation
     * @return an immutable, optionally interned resolved representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the node contains an unsupported
     *         value graph or incompatible payload shapes
     */
    public static FrozenNode fromResolvedNode(
            Node node,
            ResolvedStructuralInterner interner) {
        return FrozenNodeConverter.INSTANCE.fromResolvedNode(node, interner);
    }

    /**
     * Freezes canonical-shaped content without strict BlueId validation.
     *
     * @param node mutable canonical-shaped content to freeze
     * @return an immutable canonical-shaped representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the node has an invalid canonical
     *         payload shape or unsupported value graph
     */
    public static FrozenNode fromUncheckedCanonicalNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromUncheckedCanonicalNode(node);
    }

    /**
     * Strictly freezes an ordered canonical node list.
     *
     * @param nodes canonical nodes to freeze, or {@code null}
     * @return an immutable frozen list, or {@code null} when {@code nodes} is
     *         {@code null}
     * @throws NullPointerException when a supplied list element is
     *         {@code null}
     * @throws IllegalArgumentException when an element is not valid strict
     *         canonical Blue input
     */
    public static List<FrozenNode> fromNodes(List<Node> nodes) {
        return FrozenNodeConverter.INSTANCE.fromNodes(nodes);
    }

    /**
     * Reframes authored canonical content for the construction mode of a
     * target immutable tree without mutable conversion.
     *
     * @param authoredCanonicalValue strict canonical authored content
     * @param modeTemplate node whose canonical and Blue ID validation modes
     *        are applied
     * @return the authored value in the template's construction mode
     * @throws NullPointerException when either argument is {@code null}
     * @throws IllegalArgumentException when {@code authoredCanonicalValue} is
     *         not strict canonical content
     */
    public static FrozenNode authoredValueInModeOf(
            FrozenNode authoredCanonicalValue,
            FrozenNode modeTemplate) {
        return FrozenNodeBuilder.authoredValueInModeOf(
                authoredCanonicalValue,
                modeTemplate);
    }

    /**
     * Calculates the BlueId for an ordered canonical frozen sequence.
     *
     * @param nodes ordered canonical frozen nodes; {@code null} is treated as
     *        an empty sequence
     * @return the deterministic sequence BlueId
     * @throws IllegalArgumentException when an element is {@code null} or is
     *         not valid canonical list input
     */
    public static String calculateBlueId(List<FrozenNode> nodes) {
        return FrozenNodeIdentity.INSTANCE.blueId(nodes);
    }

    /**
     * Returns the lazily memoized exact representation key.
     *
     * @return this node's non-semantic resolved structural key
     */
    public ResolvedStructuralKey resolvedStructuralKey() {
        ResolvedStructuralKey key = resolvedStructuralKey;
        if (key == null) {
            synchronized (this) {
                key = resolvedStructuralKey;
                if (key == null) {
                    key = new ResolvedStructuralKey(this);
                    resolvedStructuralKey = key;
                }
            }
        }
        return key;
    }

    /**
     * Compares exact resolved graph content without mutable conversion.
     *
     * @param other candidate node, or {@code null}
     * @return {@code true} when both resolved representations are exact equals
     */
    public boolean sameResolvedStructure(FrozenNode other) {
        return FrozenNodeIdentity.INSTANCE.sameResolvedStructure(this, other);
    }

    /**
     * Returns a detached mutable materialization.
     *
     * @return a mutable node graph detached from this immutable representation
     */
    public Node toNode() {
        return FrozenNodeConverter.INSTANCE.toNode(this);
    }

    /**
     * Returns the lazily memoized BlueId.
     *
     * @return the deterministic BlueId of this exact representation
     */
    public String blueId() {
        String identity = blueId;
        if (identity == null) {
            synchronized (this) {
                identity = blueId;
                if (identity == null) {
                    identity = FrozenNodeIdentity.INSTANCE.blueId(this);
                    blueId = identity;
                }
            }
        }
        return identity;
    }

    /**
     * Returns the authored name, if present.
     *
     * @return the authored name, or {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * Returns a defensive public view of the scalar value graph.
     *
     * @return an immutable defensive scalar view, or {@code null}
     */
    public Object getValue() {
        return FrozenNodeConverter.INSTANCE.publicValueView(value);
    }

    /**
     * Returns the authored description, if present.
     *
     * @return the authored description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the immutable type declaration, if present.
     *
     * @return the type declaration, or {@code null}
     */
    public FrozenNode getType() {
        return type;
    }

    /**
     * Returns the immutable list-item type, if present.
     *
     * @return the list-item type, or {@code null}
     */
    public FrozenNode getItemType() {
        return itemType;
    }

    /**
     * Returns the immutable object-key type, if present.
     *
     * @return the object-key type, or {@code null}
     */
    public FrozenNode getKeyType() {
        return keyType;
    }

    /**
     * Returns the immutable object-value type, if present.
     *
     * @return the object-value type, or {@code null}
     */
    public FrozenNode getValueType() {
        return valueType;
    }

    /**
     * Returns the authored reference BlueId, if present.
     *
     * @return the reference BlueId, or {@code null}
     */
    public String getReferenceBlueId() {
        return referenceBlueId;
    }

    /**
     * Returns the immutable preprocessing directive, if present.
     *
     * @return the preprocessing directive, or {@code null}
     */
    public FrozenNode getBlue() {
        return blue;
    }

    /**
     * Returns a detached copy of the schema metadata, if present.
     *
     * @return a caller-owned schema copy, or {@code null}
     */
    public Schema getSchema() {
        return schema != null ? schema.clone() : null;
    }

    /**
     * Returns the authored merge policy, if present.
     *
     * @return the merge policy, or {@code null}
     */
    public String getMergePolicy() {
        return mergePolicy;
    }

    /**
     * Returns the previous-list anchor BlueId, if present.
     *
     * @return the previous-list anchor BlueId, or {@code null}
     */
    public String getPreviousBlueId() {
        return previousBlueId;
    }

    /**
     * Returns the preprocessing position overlay, if present.
     *
     * @return the position overlay, or {@code null}
     */
    public Integer getPosition() {
        return position;
    }

    /**
     * Reports whether inline scalar syntax was used.
     *
     * @return {@code true} when the scalar originated from inline syntax
     */
    public boolean isInlineValue() {
        return inlineValue;
    }

    /**
     * Returns the immutable list payload, if present.
     *
     * @return the immutable list payload, or {@code null}
     */
    public List<FrozenNode> getItems() {
        return items;
    }

    /**
     * Returns the immutable ordinary-property payload, if present.
     *
     * @return the immutable property map, or {@code null}
     */
    public Map<String, FrozenNode> getProperties() {
        return properties;
    }

    /**
     * Returns the distinguished immutable contracts child, if present.
     *
     * @return the contracts child, or {@code null}
     */
    public FrozenNode getContracts() {
        return contracts;
    }

    /**
     * Returns an object child, including the distinguished contracts child.
     *
     * @param key raw object-property key
     * @return the selected child, or {@code null} when it is absent
     */
    public FrozenNode property(String key) {
        return FrozenNodeNavigator.INSTANCE.property(this, key);
    }

    /**
     * Returns a list item by zero-based index.
     *
     * @param index zero-based list index
     * @return the selected item, or {@code null} when it is absent
     */
    public FrozenNode item(int index) {
        return FrozenNodeNavigator.INSTANCE.item(this, index);
    }

    /**
     * Resolves an RFC 6901 pointer from this node.
     *
     * @param pointer encoded RFC 6901 pointer
     * @return the selected node, or {@code null} when the path is absent
     */
    public FrozenNode at(String pointer) {
        return FrozenNodeNavigator.INSTANCE.at(this, pointer);
    }

    /**
     * Resolves decoded RFC 6901 pointer segments from this node.
     *
     * @param pointerSegments decoded path segments; {@code null} selects this
     *        node
     * @return the selected node, or {@code null} when the path is absent
     */
    public FrozenNode at(List<String> pointerSegments) {
        return FrozenNodeNavigator.INSTANCE.at(this, pointerSegments);
    }

    /**
     * Builds an immutable RFC 6901 path index including this root.
     *
     * @return every reachable node keyed by its encoded RFC 6901 path
     */
    public Map<String, FrozenNode> pathIndex() {
        return FrozenNodeNavigator.INSTANCE.pathIndex(this);
    }

    /**
     * Returns a conservative retained-weight estimate for this graph.
     *
     * @return estimated retained bytes, with shared objects counted once
     */
    public long approximateRetainedWeightBytes() {
        return FrozenNodeRetainedWeight.graph(this);
    }

    /**
     * Returns the weight of this node and directly owned containers.
     *
     * @return estimated shallow retained bytes
     */
    public long approximateShallowRetainedWeightBytes() {
        return FrozenNodeRetainedWeight.shallow(this);
    }

    /**
     * Estimates multiple roots while deduplicating shared objects.
     *
     * @param roots roots to estimate; {@code null} roots are ignored
     * @return estimated retained bytes across the supplied roots
     */
    public static long approximateRetainedWeightBytesOf(
            FrozenNode... roots) {
        return FrozenNodeRetainedWeight.graph(roots);
    }

    /**
     * Reports whether a list payload is present.
     *
     * @return {@code true} when this node has a list payload
     */
    public boolean hasItems() {
        return items != null;
    }

    /**
     * Reports whether ordinary object properties are present.
     *
     * @return {@code true} when this node has ordinary object properties
     */
    public boolean hasProperties() {
        return properties != null;
    }

    /**
     * Reports whether this node is one pure BlueId reference.
     *
     * @return {@code true} when no modeled field accompanies the reference
     */
    public boolean isReferenceOnly() {
        return referenceBlueId != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    /**
     * Reports whether this node is one previous-list anchor.
     *
     * @return {@code true} when no modeled field accompanies the anchor
     */
    public boolean isPreviousOnly() {
        return previousBlueId != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && schema == null
                && mergePolicy == null
                && position == null
                && blue == null
                && referenceBlueId == null;
    }

    /**
     * Reports whether strict canonical shape is enforced.
     *
     * @return {@code true} for strict canonical construction mode
     */
    public boolean isStrictCanonical() {
        return strictCanonical;
    }

    /**
     * Reports whether referenced BlueIds are strictly validated.
     *
     * @return {@code true} when referenced BlueIds were validated strictly
     */
    public boolean isStrictBlueIdValidation() {
        return strictBlueIdValidation;
    }

    /**
     * Reports whether a cyclic-set reference occurs in this subtree.
     *
     * @return {@code true} when this subtree contains a cyclic-set reference
     */
    public boolean containsCyclicSetReference() {
        return containsCyclicSetReference;
    }

    /**
     * Reports whether schema metadata occurs in this subtree.
     *
     * @return {@code true} when this subtree contains schema metadata
     */
    public boolean containsSchema() {
        return containsSchema;
    }

    /**
     * Reports whether a nested typed object occurs in this subtree.
     *
     * @return {@code true} when this subtree contains a nested typed object
     */
    public boolean containsNestedTypedObjectPayload() {
        return containsNestedTypedObjectPayload;
    }

    /**
     * Reports whether no modeled field is present.
     *
     * @return {@code true} when every modeled field is absent
     */
    public boolean isEmptyNode() {
        return name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && referenceBlueId == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    /**
     * Returns a structurally sharing copy with one object child changed.
     * A {@code null} child removes the selected property.
     *
     * @param key ordinary property key or the distinguished contracts key
     * @param child replacement child, or {@code null} to remove it
     * @return an immutable copy containing the requested property edit
     * @throws IllegalArgumentException when the edit creates incompatible
     *         payload kinds or violates strict canonical shape
     */
    public FrozenNode withProperty(String key, FrozenNode child) {
        return FrozenNodeBuilder.withProperty(this, key, child, false);
    }

    /**
     * Returns a structurally sharing copy with a replacement list payload.
     *
     * @param nextItems replacement items, or {@code null} to remove the list
     * @return an immutable copy containing the replacement list payload
     * @throws NullPointerException when a replacement item is {@code null}
     * @throws IllegalArgumentException when the replacement creates
     *         incompatible payload kinds or violates strict canonical shape
     */
    public FrozenNode withItems(List<FrozenNode> nextItems) {
        return FrozenNodeBuilder.withItems(this, nextItems, false);
    }

    /**
     * Applies an immutable object overlay with structural sharing.
     * If either value is not mergeable as an object, the overlay itself is
     * returned, including {@code null}.
     *
     * @param overlay immutable overlay or replacement value
     * @return the merged object, or {@code overlay} when object merging does
     *         not apply
     */
    public FrozenNode overlayObject(FrozenNode overlay) {
        return FrozenNodeBuilder.overlayObject(this, overlay, false);
    }

    /**
     * Removes the preprocessing position overlay.
     *
     * @return this node when no position exists, otherwise an immutable copy
     *         without the position overlay
     */
    public FrozenNode withoutPosition() {
        return FrozenNodeBuilder.withoutPosition(this);
    }

    Object frozenValue() {
        return value;
    }

    Schema frozenSchemaView() {
        return schema;
    }

    boolean isListElementContext() {
        return previousAnchorContext;
    }

    boolean isConstructionModeNormalized() {
        return constructionModeNormalized;
    }

    boolean isValueOnly() {
        return value != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && items == null
                && properties == null
                && contracts == null
                && referenceBlueId == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    FrozenNode withPropertyForPatch(String key, FrozenNode child) {
        return FrozenNodeBuilder.withProperty(this, key, child, true);
    }

    FrozenNode withItemsForPatch(List<FrozenNode> nextItems) {
        return FrozenNodeBuilder.withItems(this, nextItems, true);
    }

    FrozenNode withValueForPatch(Object nextValue) {
        return FrozenNodeBuilder.withValueForPatch(this, nextValue);
    }

    FrozenNode overlayObjectForPatch(FrozenNode overlay) {
        return FrozenNodeBuilder.overlayObject(this, overlay, true);
    }

    String cachedBlueId() {
        return blueId;
    }

    ResolvedStructuralKey cachedStructuralKey() {
        return resolvedStructuralKey;
    }

    /** Callback used to reuse equal immutable resolved representations. */
    public interface ResolvedStructuralInterner {

        /**
         * Returns the retained node for an exact structural key.
         *
         * @param structuralKey exact non-semantic representation key
         * @param node newly frozen node represented by {@code structuralKey}
         * @return {@code node} or an existing structurally equal frozen node
         */
        FrozenNode intern(
                ResolvedStructuralKey structuralKey,
                FrozenNode node);
    }

    /**
     * Compatibility type for the exact immutable representation key.
     * Semantic identity must use {@link #blueId()}, not this key.
     */
    public static final class ResolvedStructuralKey {
        private final FrozenNodeStructuralKey delegate;

        private ResolvedStructuralKey(FrozenNode node) {
            this.delegate = new FrozenNodeStructuralKey(node);
        }

        FrozenNodeStructuralKey delegate() {
            return delegate;
        }

        /**
         * Compares exact resolved representation keys.
         *
         * @param other candidate key
         * @return {@code true} when the represented structures are equal
         */
        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof ResolvedStructuralKey
                    && delegate.equals(
                            ((ResolvedStructuralKey) other).delegate);
        }

        /**
         * Returns the hash code of the exact resolved representation key.
         *
         * @return a hash code consistent with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }
}
