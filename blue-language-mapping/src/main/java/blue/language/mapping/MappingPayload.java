package blue.language.mapping;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.model.wire.JsonPointer;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_ITEM_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_KEY_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_SCHEMA;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE_TYPE;
import static blue.language.model.wire.SchemaPropertyConstants.*;

/** Internal payload-shape contract shared by every mapping entry point. */
final class MappingPayload {

    private static final ThreadLocal<IdentityHashMap<Node, Boolean>>
            ACTIVE_VALIDATED_NODES =
            new ThreadLocal<IdentityHashMap<Node, Boolean>>();

    enum Kind {
        NONE("metadata-only"),
        SCALAR("scalar"),
        LIST("list"),
        OBJECT("object");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    private MappingPayload() {
    }

    static Kind requireKind(Node node, String context) {
        return requireKind(node, context, null);
    }

    private static Kind requireKind(
            Node node,
            String context,
            ValidationPath path) {
        if (node == null) {
            return Kind.NONE;
        }
        if (Nodes.isSourceNullLiteral(node)) {
            throw failure(
                    contextAtPath(context, path),
                    "Source null is authoring-only absence and must be "
                            + "removed before semantic Java mapping");
        }
        if (Nodes.isEmptyNode(node)) {
            throw failure(
                    contextAtPath(context, path),
                    "fieldless Node is an incomplete builder, not semantic "
                            + "Blue content; use Nodes.emptyObject() for {} "
                            + "or omit the field for absence");
        }

        boolean scalar = node.getRawValue() != null;
        boolean list = node.getItems() != null;
        boolean object = node.getProperties() != null;
        int payloadCount = (scalar ? 1 : 0)
                + (list ? 1 : 0)
                + (object ? 1 : 0);
        if (payloadCount > 1) {
            throw failure(
                    contextAtPath(context, path),
                    "node combines multiple payload kinds");
        }
        if (scalar) {
            return Kind.SCALAR;
        }
        if (list) {
            return Kind.LIST;
        }
        if (object) {
            return Kind.OBJECT;
        }
        return Kind.NONE;
    }

    static Kind requireCompatible(
            Node node,
            Type targetType,
            String context) {
        Kind kind = requireKind(node, context);
        if (node == null) {
            return kind;
        }

        Class<?> rawType = rawType(targetType);
        if (rawType == Node.class) {
            return kind;
        }

        boolean scalarTarget = rawType.isEnum()
                || rawType.isPrimitive()
                || ValueConverter.isSupportedType(rawType);
        boolean listTarget = rawType.isArray()
                || Collection.class.isAssignableFrom(rawType);
        boolean mapTarget = Map.class.isAssignableFrom(rawType);
        boolean dynamicTarget = rawType == Object.class;

        boolean compatible;
        switch (kind) {
            case SCALAR:
                compatible = scalarTarget || dynamicTarget;
                break;
            case LIST:
                compatible = listTarget || dynamicTarget;
                break;
            case OBJECT:
                compatible = mapTarget
                        || dynamicTarget
                        || (!scalarTarget && !listTarget);
                break;
            case NONE:
                // A resolved optional scalar/container field may retain
                // type/schema metadata while carrying no instance payload.
                // Each converter applies its target-specific policy; a typed
                // complex node remains a present object instance. This state
                // is distinct from an object payload, including exact {}.
                compatible = true;
                break;
            default:
                compatible = false;
        }

        if (!compatible) {
            throw failure(
                    context,
                    kind.displayName()
                            + " payload cannot be mapped to "
                            + targetType.getTypeName());
        }
        return kind;
    }

    static Class<?> rawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawType(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type)
                    .getGenericComponentType();
            return Array.newInstance(rawType(componentType), 0).getClass();
        }
        if (type instanceof TypeVariable<?>) {
            return Object.class;
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return upperBounds.length == 0
                    ? Object.class
                    : rawType(upperBounds[0]);
        }
        throw new IllegalArgumentException("Unsupported target type: " + type);
    }

    static void validateSemanticNodeGraph(Node node, String context) {
        validateSemanticNodeGraph(node, JsonPointer.ROOT, context);
    }

    static void validateSemanticNodeGraph(
            Node node,
            String path,
            String context) {
        validateSemanticNodeGraph(
                node,
                JsonPointer.canonicalize(path),
                context,
                new IdentityHashMap<Node, Boolean>());
    }

    static <T> T atSemanticBoundary(
            Node node,
            String context,
            Supplier<T> conversion) {
        IdentityHashMap<Node, Boolean> validated =
                ACTIVE_VALIDATED_NODES.get();
        boolean ownsValidationScope = validated == null;
        if (ownsValidationScope) {
            validated = new IdentityHashMap<>();
            ACTIVE_VALIDATED_NODES.set(validated);
        }
        try {
            if (node != null && !validated.containsKey(node)) {
                validateSemanticNodeGraph(
                        node,
                        JsonPointer.ROOT,
                        context,
                        validated);
            }
            return conversion.get();
        } finally {
            if (ownsValidationScope) {
                ACTIVE_VALIDATED_NODES.remove();
            }
        }
    }

    private static void validateSemanticNodeGraph(
            Node node,
            String path,
            String context,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.containsKey(node)) {
            return;
        }
        Set<Node> active = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Deque<ValidationVisit> pending = new ArrayDeque<>();
        pending.push(ValidationVisit.enter(
                node, ValidationPath.root(path)));
        while (!pending.isEmpty()) {
            ValidationVisit visit = pending.pop();
            if (visit.failure != null) {
                throw failure(
                        context + " at " + visit.path.render(),
                        visit.failure);
            }
            if (visit.exit) {
                active.remove(visit.node);
                visited.put(visit.node, Boolean.TRUE);
                continue;
            }
            if (visited.containsKey(visit.node)) {
                continue;
            }
            if (!active.add(visit.node)) {
                throw failure(
                        context + " at " + visit.path.render(),
                        "object cycles are not semantic Blue content");
            }
            requireKind(
                    visit.node,
                    context,
                    visit.path);
            pending.push(ValidationVisit.exit(visit.node, visit.path));
            List<ValidationVisit> children = validationChildren(
                    visit.node, visit.path);
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
    }

    private static List<ValidationVisit> validationChildren(
            Node node,
            ValidationPath path) {
        List<ValidationVisit> children = new ArrayList<>();
        addOptionalChild(children, node.getType(), path, OBJECT_TYPE);
        addOptionalChild(children, node.getItemType(), path, OBJECT_ITEM_TYPE);
        addOptionalChild(children, node.getKeyType(), path, OBJECT_KEY_TYPE);
        addOptionalChild(children, node.getValueType(), path, OBJECT_VALUE_TYPE);
        addOptionalChild(children, node.getContracts(), path, OBJECT_CONTRACTS);
        addOptionalChild(children, node.getBlue(), path, OBJECT_BLUE);
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                ValidationPath childPath = path.child(entry.getKey());
                addRequiredChild(
                        children,
                        entry.getValue(),
                        childPath,
                        "Java-null object member is not semantic Blue "
                                + "content; omit the property for absence");
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                addRequiredChild(
                        children,
                        node.getItems().get(index),
                        path.child(String.valueOf(index)),
                        "Java-null list member is not semantic Blue content");
            }
        }
        addSchemaChildren(children, node.getSchema(), path.child(OBJECT_SCHEMA));
        return children;
    }

    private static void addSchemaChildren(
            List<ValidationVisit> children,
            Schema schema,
            ValidationPath schemaPath) {
        if (schema == null) {
            return;
        }
        addOptionalChild(children, schema.getRequired(), schemaPath, KEY_REQUIRED);
        addOptionalChild(children, schema.getMinLength(), schemaPath, KEY_MIN_LENGTH);
        addOptionalChild(children, schema.getMaxLength(), schemaPath, KEY_MAX_LENGTH);
        addOptionalChild(children, schema.getMinimum(), schemaPath, KEY_MINIMUM);
        addOptionalChild(children, schema.getMaximum(), schemaPath, KEY_MAXIMUM);
        addOptionalChild(
                children,
                schema.getExclusiveMinimum(),
                schemaPath,
                KEY_EXCLUSIVE_MINIMUM);
        addOptionalChild(
                children,
                schema.getExclusiveMaximum(),
                schemaPath,
                KEY_EXCLUSIVE_MAXIMUM);
        addOptionalChild(children, schema.getMultipleOf(), schemaPath, KEY_MULTIPLE_OF);
        addOptionalChild(children, schema.getMinItems(), schemaPath, KEY_MIN_ITEMS);
        addOptionalChild(children, schema.getMaxItems(), schemaPath, KEY_MAX_ITEMS);
        addOptionalChild(children, schema.getUniqueItems(), schemaPath, KEY_UNIQUE_ITEMS);
        addOptionalChild(children, schema.getMinFields(), schemaPath, KEY_MIN_FIELDS);
        addOptionalChild(children, schema.getMaxFields(), schemaPath, KEY_MAX_FIELDS);
        if (schema.getEnum() != null) {
            ValidationPath enumPath = schemaPath.child(KEY_ENUM);
            for (int index = 0; index < schema.getEnum().size(); index++) {
                ValidationPath valuePath = enumPath.child(
                        String.valueOf(index));
                Node enumValue = schema.getEnum().get(index);
                if (enumValue == null) {
                    children.add(ValidationVisit.failure(
                            valuePath,
                            "Java-null schema enum member is not semantic "
                                    + "Blue content"));
                } else {
                    children.add(ValidationVisit.enter(
                            enumValue, valuePath));
                    if (!Nodes.isSchemaEnumValue(enumValue)) {
                        children.add(ValidationVisit.failure(
                                valuePath,
                                "schema enum member must be a scalar value, "
                                        + "explicit scalar node, or pure "
                                        + "reference"));
                    }
                }
            }
        }
    }

    private static void addOptionalChild(
            List<ValidationVisit> children,
            Node child,
            ValidationPath parentPath,
            String segment) {
        if (child != null) {
            children.add(ValidationVisit.enter(
                    child, parentPath.child(segment)));
        }
    }

    private static void addRequiredChild(
            List<ValidationVisit> children,
            Node child,
            ValidationPath path,
            String failure) {
        children.add(child != null
                ? ValidationVisit.enter(child, path)
                : ValidationVisit.failure(path, failure));
    }

    static IllegalArgumentException failure(
            String context,
            String message) {
        String location = context == null || context.trim().isEmpty()
                ? "mapping input"
                : context;
        return new IllegalArgumentException(location + ": " + message);
    }

    private static String contextAtPath(
            String context,
            ValidationPath path) {
        return path == null
                ? context
                : context + " at " + path.render();
    }

    static RuntimeException nestedFailure(
            String context,
            RuntimeException cause) {
        if (cause instanceof IllegalArgumentException
                && cause.getMessage() != null
                && cause.getMessage().startsWith(context + ":")) {
            return (IllegalArgumentException) cause;
        }
        String detail = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
        if (cause instanceof IllegalStateException) {
            return new IllegalStateException(context + ": " + detail, cause);
        }
        if (cause instanceof IllegalArgumentException) {
            return new IllegalArgumentException(context + ": " + detail, cause);
        }
        return new RuntimeException(context + ": " + detail, cause);
    }

    private static final class ValidationVisit {
        private final Node node;
        private final ValidationPath path;
        private final boolean exit;
        private final String failure;

        private ValidationVisit(
                Node node,
                ValidationPath path,
                boolean exit,
                String failure) {
            this.node = node;
            this.path = path;
            this.exit = exit;
            this.failure = failure;
        }

        private static ValidationVisit enter(
                Node node, ValidationPath path) {
            return new ValidationVisit(node, path, false, null);
        }

        private static ValidationVisit exit(
                Node node, ValidationPath path) {
            return new ValidationVisit(node, path, true, null);
        }

        private static ValidationVisit failure(
                ValidationPath path, String failure) {
            return new ValidationVisit(null, path, false, failure);
        }
    }

    private static final class ValidationPath {
        private final ValidationPath parent;
        private final String root;
        private final String segment;

        private ValidationPath(
                ValidationPath parent,
                String root,
                String segment) {
            this.parent = parent;
            this.root = root;
            this.segment = segment;
        }

        private static ValidationPath root(String root) {
            return new ValidationPath(null, root, null);
        }

        private ValidationPath child(String segment) {
            return new ValidationPath(this, null, segment);
        }

        private String render() {
            Deque<String> segments = new ArrayDeque<>();
            ValidationPath cursor = this;
            while (cursor.parent != null) {
                segments.push(cursor.segment);
                cursor = cursor.parent;
            }
            String rendered = cursor.root;
            while (!segments.isEmpty()) {
                rendered = JsonPointer.append(rendered, segments.pop());
            }
            return rendered;
        }
    }
}
