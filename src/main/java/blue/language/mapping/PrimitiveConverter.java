package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

class PrimitiveConverter implements Converter<Object> {
    @Override
    public Object convert(Node node, Type targetType) {
        if (targetType instanceof Class<?>) {
            return ValueConverter.convertValue(node, (Class<?>) targetType);
        } else {
            throw new IllegalArgumentException("Unsupported target type for primitive conversion: " + targetType);
        }
    }
}