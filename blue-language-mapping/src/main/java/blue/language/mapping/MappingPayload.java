package blue.language.mapping;

import blue.language.model.Node;
import blue.language.model.Nodes;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.Map;

/** Internal payload-shape contract shared by every mapping entry point. */
final class MappingPayload {

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
        if (node == null) {
            return Kind.NONE;
        }
        if (Nodes.isSourceNullLiteral(node)) {
            throw failure(
                    context,
                    "Source null is authoring-only absence and must be "
                            + "removed before semantic Java mapping");
        }
        if (Nodes.isEmptyNode(node)) {
            throw failure(
                    context,
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
                    context,
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
                compatible = dynamicTarget
                        || (!scalarTarget && !listTarget && !mapTarget);
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

    static IllegalArgumentException failure(
            String context,
            String message) {
        String location = context == null || context.trim().isEmpty()
                ? "mapping input"
                : context;
        return new IllegalArgumentException(location + ": " + message);
    }

    static IllegalArgumentException nestedFailure(
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
        return new IllegalArgumentException(context + ": " + detail, cause);
    }
}
