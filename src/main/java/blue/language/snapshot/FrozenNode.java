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

    /** Creates a strict canonical node with no fields. */
    public static FrozenNode empty() {
        return FrozenNodeBuilder.builder().build();
    }

    /** Strictly validates and defensively freezes canonical content. */
    public static FrozenNode fromNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromNode(node);
    }

    /** Defensively freezes a completed resolved view. */
    public static FrozenNode fromResolvedNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromResolvedNode(node);
    }

    /**
     * Freezes a resolved view and offers each bottom-up exact representation
     * to an optional structural interner.
     */
    public static FrozenNode fromResolvedNode(
            Node node,
            ResolvedStructuralInterner interner) {
        return FrozenNodeConverter.INSTANCE.fromResolvedNode(node, interner);
    }

    /** Freezes canonical-shaped content without strict BlueId validation. */
    public static FrozenNode fromUncheckedCanonicalNode(Node node) {
        return FrozenNodeConverter.INSTANCE.fromUncheckedCanonicalNode(node);
    }

    /** Strictly freezes a canonical node list. */
    public static List<FrozenNode> fromNodes(List<Node> nodes) {
        return FrozenNodeConverter.INSTANCE.fromNodes(nodes);
    }

    /**
     * Reframes authored canonical content for the construction mode of a
     * target immutable tree without mutable conversion.
     */
    public static FrozenNode authoredValueInModeOf(
            FrozenNode authoredCanonicalValue,
            FrozenNode modeTemplate) {
        return FrozenNodeBuilder.authoredValueInModeOf(
                authoredCanonicalValue,
                modeTemplate);
    }

    /** Calculates the BlueId for an ordered canonical frozen sequence. */
    public static String calculateBlueId(List<FrozenNode> nodes) {
        return FrozenNodeIdentity.INSTANCE.blueId(nodes);
    }

    /** Returns the lazily memoized exact representation key. */
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

    /** Compares exact resolved graph content without mutable conversion. */
    public boolean sameResolvedStructure(FrozenNode other) {
        return FrozenNodeIdentity.INSTANCE.sameResolvedStructure(this, other);
    }

    /** Returns a detached mutable materialization. */
    public Node toNode() {
        return FrozenNodeConverter.INSTANCE.toNode(this);
    }

    /** Returns the lazily memoized BlueId. */
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

    /** Returns the authored name, or {@code null}. */
    public String getName() {
        return name;
    }

    /** Returns a defensive public view of the scalar value graph. */
    public Object getValue() {
        return FrozenNodeConverter.INSTANCE.publicValueView(value);
    }

    /** Returns the authored description, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** Returns the type declaration, or {@code null}. */
    public FrozenNode getType() {
        return type;
    }

    /** Returns the list-item type, or {@code null}. */
    public FrozenNode getItemType() {
        return itemType;
    }

    /** Returns the object-key type, or {@code null}. */
    public FrozenNode getKeyType() {
        return keyType;
    }

    /** Returns the object-value type, or {@code null}. */
    public FrozenNode getValueType() {
        return valueType;
    }

    /** Returns the authored reference BlueId, or {@code null}. */
    public String getReferenceBlueId() {
        return referenceBlueId;
    }

    /** Returns the preprocessing directive, or {@code null}. */
    public FrozenNode getBlue() {
        return blue;
    }

    /** Returns a detached schema copy, or {@code null}. */
    public Schema getSchema() {
        return schema != null ? schema.clone() : null;
    }

    /** Returns the merge policy, or {@code null}. */
    public String getMergePolicy() {
        return mergePolicy;
    }

    /** Returns the previous-list anchor BlueId, or {@code null}. */
    public String getPreviousBlueId() {
        return previousBlueId;
    }

    /** Returns the preprocessing position overlay, or {@code null}. */
    public Integer getPosition() {
        return position;
    }

    /** Reports whether inline scalar syntax was used. */
    public boolean isInlineValue() {
        return inlineValue;
    }

    /** Returns the immutable list payload, or {@code null}. */
    public List<FrozenNode> getItems() {
        return items;
    }

    /** Returns the immutable property payload, or {@code null}. */
    public Map<String, FrozenNode> getProperties() {
        return properties;
    }

    /** Returns the contracts child, or {@code null}. */
    public FrozenNode getContracts() {
        return contracts;
    }

    /** Returns an object child, including the contracts child. */
    public FrozenNode property(String key) {
        return FrozenNodeNavigator.INSTANCE.property(this, key);
    }

    /** Returns a list item, or {@code null} when absent. */
    public FrozenNode item(int index) {
        return FrozenNodeNavigator.INSTANCE.item(this, index);
    }

    /** Resolves an RFC 6901 pointer. */
    public FrozenNode at(String pointer) {
        return FrozenNodeNavigator.INSTANCE.at(this, pointer);
    }

    /** Resolves decoded RFC 6901 pointer segments. */
    public FrozenNode at(List<String> pointerSegments) {
        return FrozenNodeNavigator.INSTANCE.at(this, pointerSegments);
    }

    /** Builds an immutable RFC 6901 path index including the root. */
    public Map<String, FrozenNode> pathIndex() {
        return FrozenNodeNavigator.INSTANCE.pathIndex(this);
    }

    /** Returns a conservative retained-weight estimate for this graph. */
    public long approximateRetainedWeightBytes() {
        return FrozenNodeRetainedWeight.graph(this);
    }

    /** Returns the weight of this node and directly owned containers. */
    public long approximateShallowRetainedWeightBytes() {
        return FrozenNodeRetainedWeight.shallow(this);
    }

    /** Estimates multiple roots while deduplicating shared objects. */
    public static long approximateRetainedWeightBytesOf(
            FrozenNode... roots) {
        return FrozenNodeRetainedWeight.graph(roots);
    }

    /** Reports whether a list payload is present. */
    public boolean hasItems() {
        return items != null;
    }

    /** Reports whether ordinary object properties are present. */
    public boolean hasProperties() {
        return properties != null;
    }

    /** Reports whether this node is one pure BlueId reference. */
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

    /** Reports whether this node is one previous-list anchor. */
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

    /** Reports whether strict canonical shape is enforced. */
    public boolean isStrictCanonical() {
        return strictCanonical;
    }

    /** Reports whether referenced BlueIds are strictly validated. */
    public boolean isStrictBlueIdValidation() {
        return strictBlueIdValidation;
    }

    /** Reports whether a cyclic-set reference occurs in this subtree. */
    public boolean containsCyclicSetReference() {
        return containsCyclicSetReference;
    }

    /** Reports whether schema metadata occurs in this subtree. */
    public boolean containsSchema() {
        return containsSchema;
    }

    /** Reports whether a nested typed object occurs in this subtree. */
    public boolean containsNestedTypedObjectPayload() {
        return containsNestedTypedObjectPayload;
    }

    /** Reports whether no modeled field is present. */
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

    /** Returns a structurally sharing copy with one object child changed. */
    public FrozenNode withProperty(String key, FrozenNode child) {
        return FrozenNodeBuilder.withProperty(this, key, child, false);
    }

    /** Returns a structurally sharing copy with a replacement list payload. */
    public FrozenNode withItems(List<FrozenNode> nextItems) {
        return FrozenNodeBuilder.withItems(this, nextItems, false);
    }

    /** Applies a non-null immutable object overlay. */
    public FrozenNode overlayObject(FrozenNode overlay) {
        return FrozenNodeBuilder.overlayObject(this, overlay, false);
    }

    /** Removes the preprocessing position overlay. */
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

        /** Returns the retained node for an exact structural key. */
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

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof ResolvedStructuralKey
                    && delegate.equals(
                            ((ResolvedStructuralKey) other).delegate);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }
}
