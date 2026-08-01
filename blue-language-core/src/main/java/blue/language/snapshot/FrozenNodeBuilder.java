package blue.language.snapshot;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;

/**
 * Owns construction, mode normalization, and structurally sharing edits for
 * immutable nodes.
 */
public final class FrozenNodeBuilder {

    String name;
    String description;
    FrozenNode type;
    FrozenNode itemType;
    FrozenNode keyType;
    FrozenNode valueType;
    Object nodeValue;
    List<FrozenNode> items;
    Map<String, FrozenNode> properties;
    FrozenNode contracts;
    String referenceBlueId;
    Schema schema;
    String mergePolicy;
    String previousBlueId;
    Integer position;
    FrozenNode blue;
    boolean inlineValue;
    boolean strictCanonical = true;
    boolean strictBlueIdValidation = true;
    boolean previousAnchorContext;
    boolean eagerBlueId = true;

    private FrozenNodeBuilder() {
    }

    static FrozenNodeBuilder builder() {
        return new FrozenNodeBuilder();
    }

    static FrozenNodeBuilder from(FrozenNode node) {
        return builder()
                .name(node.name)
                .description(node.description)
                .type(node.type)
                .itemType(node.itemType)
                .keyType(node.keyType)
                .valueType(node.valueType)
                .frozenValue(node.value)
                .items(node.items)
                .properties(node.properties)
                .contracts(node.contracts)
                .referenceBlueId(node.referenceBlueId)
                .schema(node.schema)
                .mergePolicy(node.mergePolicy)
                .previousBlueId(node.previousBlueId)
                .position(node.position)
                .blue(node.blue)
                .inlineValue(node.inlineValue)
                .strictCanonical(node.strictCanonical)
                .strictBlueIdValidation(node.strictBlueIdValidation)
                .previousAnchorContext(node.previousAnchorContext);
    }

    /** Reframes authored canonical content for an immutable target mode. */
    public static FrozenNode authoredValueInModeOf(
            FrozenNode authoredCanonicalValue,
            FrozenNode modeTemplate) {
        FrozenNode source = Objects.requireNonNull(
                authoredCanonicalValue,
                "authoredCanonicalValue");
        FrozenNode template = Objects.requireNonNull(
                modeTemplate,
                "modeTemplate");
        if (!source.strictCanonical) {
            throw new IllegalArgumentException(
                    "Authored frozen values must be canonical");
        }
        if (source.strictCanonical == template.strictCanonical
                && source.strictBlueIdValidation
                == template.strictBlueIdValidation
                && source.constructionModeNormalized) {
            return source;
        }
        return copyInConstructionMode(
                source,
                template.strictCanonical,
                template.strictBlueIdValidation,
                false);
    }

    static FrozenNode withProperty(
            FrozenNode node,
            String key,
            FrozenNode child,
            boolean deferBlueId) {
        if (OBJECT_CONTRACTS.equals(key)) {
            FrozenNodeBuilder next = from(node).contracts(
                    child == null
                            || node.strictCanonical && child.isEmptyNode()
                            ? null
                            : child);
            return finish(next, deferBlueId);
        }
        Map<String, FrozenNode> next = node.properties != null
                ? new LinkedHashMap<>(node.properties)
                : new LinkedHashMap<String, FrozenNode>();
        if (child == null
                || node.strictCanonical && child.isEmptyNode()) {
            next.remove(key);
        } else {
            next.put(key, child);
        }
        return finish(
                from(node).properties(next.isEmpty() ? null : next),
                deferBlueId);
    }

    static FrozenNode withItems(
            FrozenNode node,
            List<FrozenNode> nextItems,
            boolean deferBlueId) {
        return finish(from(node).items(nextItems), deferBlueId);
    }

    static FrozenNode withValueForPatch(
            FrozenNode node,
            Object nextValue) {
        return from(node)
                .frozenValue(nextValue)
                .deferBlueId()
                .build();
    }

    static FrozenNode overlayObject(
            FrozenNode node,
            FrozenNode overlay,
            boolean deferBlueId) {
        if (!isMergeableObject(node) || !isMergeableObject(overlay)) {
            return overlay;
        }
        FrozenNodeBuilder merged = from(node);
        if (overlay.properties != null) {
            Map<String, FrozenNode> nextProperties = node.properties != null
                    ? new LinkedHashMap<>(node.properties)
                    : new LinkedHashMap<String, FrozenNode>();
            nextProperties.putAll(overlay.properties);
            merged.properties(nextProperties);
        }
        if (overlay.contracts != null) merged.contracts(overlay.contracts);
        if (overlay.type != null) merged.type(overlay.type);
        if (overlay.itemType != null) merged.itemType(overlay.itemType);
        if (overlay.keyType != null) merged.keyType(overlay.keyType);
        if (overlay.valueType != null) merged.valueType(overlay.valueType);
        if (overlay.blue != null) merged.blue(overlay.blue);
        if (overlay.schema != null) merged.schema(overlay.schema);
        if (overlay.name != null) merged.name(overlay.name);
        if (overlay.description != null) {
            merged.description(overlay.description);
        }
        if (overlay.mergePolicy != null) {
            merged.mergePolicy(overlay.mergePolicy);
        }
        if (overlay.previousBlueId != null) {
            merged.previousBlueId(overlay.previousBlueId);
        }
        if (overlay.position != null) merged.position(overlay.position);
        return finish(merged, deferBlueId);
    }

    static FrozenNode withoutPosition(FrozenNode node) {
        return node.position == null
                ? node
                : from(node).position(null).build();
    }

    static boolean constructionModeNormalized(FrozenNode node) {
        if (!normalizedChild(node, node.type, false)
                || !normalizedChild(node, node.itemType, false)
                || !normalizedChild(node, node.keyType, false)
                || !normalizedChild(node, node.valueType, false)
                || !normalizedChild(node, node.contracts, false)
                || !normalizedChild(node, node.blue, false)) {
            return false;
        }
        if (node.items != null) {
            for (FrozenNode item : node.items) {
                if (!normalizedChild(node, item, true)) {
                    return false;
                }
            }
        }
        if (node.properties != null) {
            for (FrozenNode property : node.properties.values()) {
                if (!normalizedChild(node, property, false)) {
                    return false;
                }
            }
        }
        return true;
    }

    static void validatePayloadShape(FrozenNode node) {
        int payloadKinds = 0;
        if (node.value != null) payloadKinds++;
        if (node.items != null) payloadKinds++;
        if (node.properties != null && !node.properties.isEmpty()) {
            payloadKinds++;
        }
        if (payloadKinds > 1) {
            throw new IllegalArgumentException(
                    "A Blue node may contain only one payload kind: value, items, or object fields.");
        }
        if (node.strictCanonical
                && node.referenceBlueId != null
                && !node.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "\"blueId\" nodes must be reference-only and cannot contain sibling fields.");
        }
        if (node.strictCanonical && node.previousBlueId != null) {
            if (!node.isPreviousOnly()) {
                throw new IllegalArgumentException(
                        "\"$previous\" list anchors must be single-key list items.");
            }
            if (!node.previousAnchorContext) {
                throw new IllegalArgumentException(
                        "\"$previous\" is valid only as the first list item in direct BlueId input.");
            }
        }
        if (node.strictCanonical && node.blue != null) {
            throw new IllegalArgumentException(
                    "\"blue\" is a preprocessing directive and must not appear in canonical BlueId input.");
        }
        if (node.strictCanonical && node.position != null) {
            throw new IllegalArgumentException(
                    "\"$pos\" overlays are not valid direct BlueId input.");
        }
    }

    FrozenNodeBuilder name(String value) {
        this.name = value;
        return this;
    }

    FrozenNodeBuilder description(String value) {
        this.description = value;
        return this;
    }

    FrozenNodeBuilder type(FrozenNode value) {
        this.type = value;
        return this;
    }

    FrozenNodeBuilder itemType(FrozenNode value) {
        this.itemType = value;
        return this;
    }

    FrozenNodeBuilder keyType(FrozenNode value) {
        this.keyType = value;
        return this;
    }

    FrozenNodeBuilder valueType(FrozenNode value) {
        this.valueType = value;
        return this;
    }

    FrozenNodeBuilder value(Object value) {
        this.nodeValue = FrozenNodeConverter.freezeValue(value);
        return this;
    }

    FrozenNodeBuilder frozenValue(Object value) {
        this.nodeValue = value;
        return this;
    }

    FrozenNodeBuilder items(List<FrozenNode> value) {
        this.items = value;
        return this;
    }

    FrozenNodeBuilder properties(Map<String, FrozenNode> value) {
        this.properties = value;
        return this;
    }

    FrozenNodeBuilder contracts(FrozenNode value) {
        this.contracts = value;
        return this;
    }

    FrozenNodeBuilder referenceBlueId(String value) {
        this.referenceBlueId = value;
        return this;
    }

    FrozenNodeBuilder schema(Schema value) {
        this.schema = value != null ? value.clone() : null;
        return this;
    }

    FrozenNodeBuilder mergePolicy(String value) {
        this.mergePolicy = value;
        return this;
    }

    FrozenNodeBuilder previousBlueId(String value) {
        this.previousBlueId = value;
        return this;
    }

    FrozenNodeBuilder position(Integer value) {
        this.position = value;
        return this;
    }

    FrozenNodeBuilder blue(FrozenNode value) {
        this.blue = value;
        return this;
    }

    FrozenNodeBuilder inlineValue(boolean value) {
        this.inlineValue = value;
        return this;
    }

    FrozenNodeBuilder strictCanonical(boolean value) {
        this.strictCanonical = value;
        return this;
    }

    FrozenNodeBuilder strictBlueIdValidation(boolean value) {
        this.strictBlueIdValidation = value;
        return this;
    }

    FrozenNodeBuilder previousAnchorContext(boolean value) {
        this.previousAnchorContext = value;
        return this;
    }

    FrozenNodeBuilder deferBlueId() {
        this.eagerBlueId = false;
        return this;
    }

    FrozenNode build() {
        return new FrozenNode(this);
    }

    static List<FrozenNode> freezeList(
            List<FrozenNode> source,
            boolean strictCanonical) {
        if (source == null) {
            return null;
        }
        List<FrozenNode> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            FrozenNode node = source.get(index);
            if (strictCanonical && node.isEmptyNode()) {
                throw new IllegalArgumentException(
                        "Direct BlueId input must use { \"$empty\": true } for empty list placeholders.");
            }
            if (strictCanonical
                    && node.isPreviousOnly()
                    && index != 0) {
                throw new IllegalArgumentException(
                        "\"$previous\" must appear only as the first list item.");
            }
            result.add(node);
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, FrozenNode> freezeMap(
            Map<String, FrozenNode> source) {
        return source == null || source.isEmpty()
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static FrozenNode copyInConstructionMode(
            FrozenNode source,
            boolean targetStrictCanonical,
            boolean targetStrictBlueIdValidation,
            boolean listElement) {
        if (source == null) {
            return null;
        }
        List<FrozenNode> nextItems = null;
        if (source.items != null) {
            nextItems = new ArrayList<>(source.items.size());
            for (FrozenNode item : source.items) {
                nextItems.add(copyInConstructionMode(
                        item,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        true));
            }
        }
        Map<String, FrozenNode> nextProperties = null;
        if (source.properties != null) {
            nextProperties = new LinkedHashMap<>();
            for (Map.Entry<String, FrozenNode> entry
                    : source.properties.entrySet()) {
                nextProperties.put(
                        entry.getKey(),
                        copyInConstructionMode(
                                entry.getValue(),
                                targetStrictCanonical,
                                targetStrictBlueIdValidation,
                                false));
            }
        }
        return builder()
                .name(source.name)
                .description(source.description)
                .type(copyInConstructionMode(
                        source.type,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        false))
                .itemType(copyInConstructionMode(
                        source.itemType,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        false))
                .keyType(copyInConstructionMode(
                        source.keyType,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        false))
                .valueType(copyInConstructionMode(
                        source.valueType,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        false))
                .frozenValue(source.value)
                .items(nextItems)
                .properties(nextProperties)
                .contracts(copyInConstructionMode(
                        source.contracts,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        false))
                .referenceBlueId(source.referenceBlueId)
                .schema(source.schema)
                .mergePolicy(source.mergePolicy)
                .previousBlueId(source.previousBlueId)
                .position(source.position)
                .blue(copyInConstructionMode(
                        source.blue,
                        targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        false))
                .inlineValue(source.inlineValue)
                .strictCanonical(targetStrictCanonical)
                .strictBlueIdValidation(targetStrictBlueIdValidation)
                .previousAnchorContext(listElement)
                .build();
    }

    private static boolean normalizedChild(
            FrozenNode parent,
            FrozenNode child,
            boolean listElement) {
        return child == null
                || child.strictCanonical == parent.strictCanonical
                && child.strictBlueIdValidation
                == parent.strictBlueIdValidation
                && child.previousAnchorContext == listElement
                && child.constructionModeNormalized;
    }

    private static boolean isMergeableObject(FrozenNode node) {
        return node != null
                && node.value == null
                && node.items == null
                && !node.isReferenceOnly()
                && node.previousBlueId == null;
    }

    private static FrozenNode finish(
            FrozenNodeBuilder builder,
            boolean deferBlueId) {
        return (deferBlueId ? builder.deferBlueId() : builder).build();
    }
}
