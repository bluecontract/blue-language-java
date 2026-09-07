package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/** Converts an exact scalar spelling to a constant of the requested enum. */
public class EnumConverter implements Converter<Enum<?>> {

    /** Creates a stateless enum converter. */
    public EnumConverter() {
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Enum<?> convert(Node node, Type targetType) {
        return MappingPayload.atSemanticBoundary(
                node,
                "enum mapping",
                () -> convertValidated(node, targetType));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Enum<?> convertValidated(Node node, Type targetType) {
        if (node == null) {
            return null;
        }
        MappingPayload.Kind payloadKind = MappingPayload.requireCompatible(
                node,
                targetType,
                "enum mapping");
        if (payloadKind == MappingPayload.Kind.NONE) {
            return null;
        }
        if (targetType instanceof Class<?> && ((Class<?>) targetType).isEnum()) {
            String value = node.getValue().toString();
            return Enum.valueOf((Class<Enum>) targetType, value);
        } else {
            throw new IllegalArgumentException("Unsupported target type for Enum conversion: " + targetType);
        }
    }
}
