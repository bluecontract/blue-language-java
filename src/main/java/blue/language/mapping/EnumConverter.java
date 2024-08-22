package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

public class EnumConverter implements Converter<Enum<?>> {
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Enum<?> convert(Node node, Type targetType) {
        if (targetType instanceof Class<?> && ((Class<?>) targetType).isEnum()) {
            String value = node.getValue().toString();
            return Enum.valueOf((Class<Enum>) targetType, value);
        } else {
            throw new IllegalArgumentException("Unsupported target type for Enum conversion: " + targetType);
        }
    }
}